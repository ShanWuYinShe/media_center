import { lookup as getaddrinfoLookup, Resolver } from "node:dns/promises";
import { isIP } from "node:net";

export type ResolvedAddress = { address: string; family: number };

export type LookupFn = (
  hostname: string,
  options: { all: true; verbatim: true },
) => Promise<ResolvedAddress[]>;

/** 解析结果指向环回/私网/内网地址时抛出,调用方据此返回 400(SSRF 防护)。 */
export class BlockedAddressError extends Error {
  constructor() {
    super("resolved address is not allowed");
    this.name = "BlockedAddressError";
  }
}

// getaddrinfo(EAI_*)与 c-ares(c-ares 自有码)两套可恢复错误码。拥塞/超时类视为
// 瞬时,交给上层退避重试 → 把"慢"吸收为延迟而非直接抛错。
const TRANSIENT_DNS_CODES = new Set([
  "EAI_AGAIN",
  "ETIMEDOUT",
  "ETIMEOUT",
  "ESERVFAIL",
  "EREFUSED",
  "ECONNREFUSED",
  "ECONNRESET",
]);

// c-ares 返回这些码表示"该域名确实没有此类记录",不应再重试 c-ares,
// 而是回退到系统解析器(getaddrinfo,可读 /etc/hosts、NSS)。
const CARES_NO_RECORD_CODES = new Set([
  "ENOTFOUND",
  "ENODATA",
  "EBADNAME",
  "ENOTIMP",
]);

export interface AssetDnsResolverOptions {
  isBlockedAddress: (address: string) => boolean;
  ttlMs?: number;
  maxRetries?: number;
  retryBackoffMs?: readonly number[];
  lookupFn?: LookupFn;
  now?: () => number;
  sleepFn?: (ms: number) => Promise<void>;
}

type CacheEntry = { addresses: ResolvedAddress[]; expiresAt: number };

/**
 * asset 代理的 DNS 解析器。
 *
 * 解决的根因:每个 asset 请求会在预校验(validateAssetProxyTarget)和 undici
 * 连接器(validatingLookup)各触发一次 `dns.lookup`(getaddrinfo,走 libuv 线程池,
 * 默认仅 4 线程)。当一屏几十个封面并发加载时,数十个 getaddrinfo 把线程池打满,
 * 整批解析超时(EAI_AGAIN/ETIMEDOUT)→ 同一时刻整批 502。
 *
 * 三重收敛:
 * 1. 短 TTL 缓存:同一域名的重复请求直接命中缓存,不再 getaddrinfo。
 * 2. single-flight:缓存冷启动时同域名并发只触发一次解析,其余等待同一 Promise。
 * 3. 瞬时错误重试:对 EAI_AGAIN 等可恢复错误做有限退避重试。
 *
 * 只缓存通过 SSRF 校验的成功结果;TTL 短,且缓存内每个地址都已校验非内网,
 * 因此不会引入 DNS-rebinding 风险。
 */
export class AssetDnsResolver {
  private readonly isBlockedAddress: (address: string) => boolean;
  private readonly ttlMs: number;
  private readonly maxRetries: number;
  private readonly retryBackoffMs: readonly number[];
  private readonly lookupFn: LookupFn;
  private readonly now: () => number;
  private readonly sleepFn: (ms: number) => Promise<void>;
  private readonly cache = new Map<string, CacheEntry>();
  private readonly inflight = new Map<string, Promise<ResolvedAddress[]>>();

  constructor(options: AssetDnsResolverOptions) {
    this.isBlockedAddress = options.isBlockedAddress;
    this.ttlMs = options.ttlMs ?? 30_000;
    this.maxRetries = options.maxRetries ?? 5;
    this.retryBackoffMs = options.retryBackoffMs ?? [
      100, 250, 500, 1000, 2000,
    ];
    this.lookupFn = options.lookupFn ?? defaultLookup;
    this.now = options.now ?? Date.now;
    this.sleepFn = options.sleepFn ?? defaultSleep;
  }

  async resolve(hostname: string): Promise<ResolvedAddress[]> {
    const key = hostname.toLowerCase();
    const cached = this.cache.get(key);
    if (cached && cached.expiresAt > this.now()) {
      return cached.addresses;
    }
    const existing = this.inflight.get(key);
    if (existing) {
      return existing;
    }
    const pending = this.resolveAndValidate(hostname)
      .then((addresses) => {
        this.cache.set(key, {
          addresses,
          expiresAt: this.now() + this.ttlMs,
        });
        return addresses;
      })
      .finally(() => {
        this.inflight.delete(key);
      });
    this.inflight.set(key, pending);
    return pending;
  }

  private async resolveAndValidate(
    hostname: string,
  ): Promise<ResolvedAddress[]> {
    let lastError: unknown;
    for (let attempt = 0; attempt <= this.maxRetries; attempt += 1) {
      let resolved: ResolvedAddress[];
      try {
        resolved = await this.lookupFn(hostname, {
          all: true,
          verbatim: true,
        });
      } catch (error) {
        if (!isTransientDnsError(error) || attempt >= this.maxRetries) {
          throw error;
        }
        lastError = error;
        await this.sleepFn(this.backoffFor(attempt));
        continue;
      }
      if (
        resolved.length === 0 ||
        resolved.some(({ address }) => this.isBlockedAddress(address))
      ) {
        throw new BlockedAddressError();
      }
      return resolved;
    }
    throw lastError instanceof Error ? lastError : new BlockedAddressError();
  }

  private backoffFor(attempt: number): number {
    if (this.retryBackoffMs.length === 0) {
      return 0;
    }
    return this.retryBackoffMs[
      Math.min(attempt, this.retryBackoffMs.length - 1)
    ];
  }
}

function isTransientDnsError(error: unknown): boolean {
  const code = (error as NodeJS.ErrnoException | null)?.code;
  return typeof code === "string" && TRANSIENT_DNS_CODES.has(code);
}

function isCaresNoRecordError(error: unknown): boolean {
  const code = (error as NodeJS.ErrnoException | null)?.code;
  return typeof code === "string" && CARES_NO_RECORD_CODES.has(code);
}

const caresResolver = new Resolver();

/**
 * 默认解析:优先用 c-ares(node:dns 的 Resolver.resolve4/6)。
 *
 * 关键点:c-ares 在独立的 UDP/TCP 套接字上异步查询,**不占用 libuv 线程池**,
 * 因此即使线程池被其它阻塞型调用占满,DNS 也只会变慢、排队,而不会被挤掉。
 * 这从机制上消除了"线程池不足 → getaddrinfo 直接 EAI_AGAIN → 502"的路径。
 *
 * 仅当 c-ares 明确返回"无此记录"(可能是 /etc/hosts、NSS 特殊解析)时,才回退到
 * getaddrinfo;c-ares 的瞬时错误(超时/拒绝)直接上抛,交由上层退避重试。
 */
async function defaultLookup(
  hostname: string,
  options: { all: true; verbatim: true },
): Promise<ResolvedAddress[]> {
  const literal = isIP(hostname);
  if (literal === 4) {
    return [{ address: hostname, family: 4 }];
  }
  if (literal === 6) {
    return [{ address: hostname, family: 6 }];
  }
  try {
    return await caresResolveBoth(hostname);
  } catch (error) {
    if (!isCaresNoRecordError(error)) {
      throw error;
    }
  }
  return (await getaddrinfoLookup(
    hostname,
    options,
  )) as ResolvedAddress[];
}

async function caresResolveBoth(hostname: string): Promise<ResolvedAddress[]> {
  const [v4, v6] = await Promise.allSettled([
    caresResolver.resolve4(hostname),
    caresResolver.resolve6(hostname),
  ]);
  const addresses: ResolvedAddress[] = [];
  if (v4.status === "fulfilled") {
    for (const address of v4.value) {
      addresses.push({ address, family: 4 });
    }
  }
  if (v6.status === "fulfilled") {
    for (const address of v6.value) {
      addresses.push({ address, family: 6 });
    }
  }
  if (addresses.length > 0) {
    return addresses;
  }
  const errors = [v4, v6]
    .filter((result): result is PromiseRejectedResult => result.status === "rejected")
    .map((result) => result.reason);
  const transient = errors.find(isTransientDnsError);
  if (transient) {
    throw transient;
  }
  throw (
    errors[0] ??
    Object.assign(new Error("no DNS records"), { code: "ENODATA" })
  );
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
