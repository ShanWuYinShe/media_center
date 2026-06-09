import cors from "@fastify/cors";
import Fastify, { type FastifyReply, type FastifyRequest } from "fastify";
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";
import { lookup } from "node:dns/promises";
import net from "node:net";
import { Readable } from "node:stream";
import { z } from "zod";
import {
  contentCategories,
  findContentCategory,
  mediaServerConfig,
} from "./contentCategories.js";
import { ApiError } from "./errors.js";
import { BilibiliProvider } from "./providers/BilibiliProvider.js";
import { InternetArchiveProvider } from "./providers/InternetArchiveProvider.js";
import { YouTubeProvider } from "./providers/YouTubeProvider.js";
import type { VideoProvider } from "./providers/VideoProvider.js";
import { CategoryResultCache } from "./services/CategoryResultCache.js";
import { HttpResponseCache } from "./services/HttpResponseCache.js";
import {
  dashManifestPayloadFromReference,
  DASH_MANIFEST_MIME_TYPE,
  isDashManifestReference,
  parseDashManifestPayload,
  renderDashMpd,
  type DashMediaUrlRole,
} from "./services/StreamManifests.js";
import { VideoAggregator } from "./services/VideoAggregator.js";
import { fetchWithTimeout } from "./services/fetchWithTimeout.js";
import type { MediaSourceConfig, ProviderName } from "./types.js";

const searchSchema = z.object({
  q: z.string().trim().min(1).max(120),
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(40).default(20),
});

const categoryParamsSchema = z.object({ id: z.string().trim().min(1).max(80) });
const pageQuerySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(40).default(20),
});
const videoParamsSchema = z.object({ id: z.string().min(1) });
const opaqueReferenceSchema = z
  .string()
  .min(1)
  .max(128 * 1024)
  .regex(/^v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
const assetQuerySchema = z.object({
  ref: opaqueReferenceSchema,
  sig: z.string().regex(/^[a-f0-9]{64}$/i),
});
const dashManifestQuerySchema = z.object({
  ref: opaqueReferenceSchema,
  sig: z.string().regex(/^[a-f0-9]{64}$/i),
});
const MAX_ROUTE_PARAM_LENGTH = 4096;
const ASSET_SIGNING_KEY =
  process.env.MEDIA_ASSET_SIGNING_KEY?.trim() ||
  randomBytes(32).toString("hex");
const ASSET_REFERENCE_KEY = createHash("sha256")
  .update(`media-asset-reference:${ASSET_SIGNING_KEY}`)
  .digest();
const MAX_ASSET_REDIRECTS = 5;
const assetReferencePayloadSchema = z.object({
  v: z.literal(1),
  kind: z.literal("asset"),
  url: z.string().url(),
  role: z.enum(["media", "initialization"]).optional(),
});
type AssetReferencePayload = z.infer<typeof assetReferencePayloadSchema>;
const dashManifestReferencePayloadSchema = z.object({
  v: z.literal(1),
  kind: z.literal("dash-manifest"),
  payload: z
    .string()
    .min(1)
    .max(64 * 1024)
    .regex(/^[A-Za-z0-9_-]+$/),
});
type DashManifestReferencePayload = z.infer<
  typeof dashManifestReferencePayloadSchema
>;

export function buildServer() {
  const app = Fastify({
    logger: { level: process.env.LOG_LEVEL ?? "info" },
    trustProxy: true,
    disableRequestLogging: true,
    routerOptions: {
      maxParamLength: MAX_ROUTE_PARAM_LENGTH,
    },
  });
  const providers = buildProviders(mediaServerConfig.sources);
  const aggregator = new VideoAggregator(providers);
  const categoryCache = new CategoryResultCache(aggregator, contentCategories);
  const responseCache = new HttpResponseCache();
  const requestStartTimes = new WeakMap<FastifyRequest, bigint>();

  app.register(cors, { origin: true });

  app.addHook("onRequest", async (request) => {
    requestStartTimes.set(request, process.hrtime.bigint());
    if (!isHealthCheckRequest(request)) {
      request.log.info(
        { request: requestLogContext(request) },
        "request started",
      );
    }
  });

  app.addHook("onResponse", async (request, reply) => {
    if (!isHealthCheckRequest(request)) {
      request.log.info(
        {
          request: requestLogContext(request),
          response: responseLogContext(reply),
          responseTimeMs: elapsedMs(requestStartTimes.get(request)),
        },
        "request completed",
      );
    }
    requestStartTimes.delete(request);
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      logApiError(request, error);
      return reply.status(error.statusCode).send({
        error: {
          code: error.code,
          message: publicErrorMessage(error),
        },
      });
    }
    if (error instanceof z.ZodError) {
      request.log.warn(
        { request: requestLogContext(request), issues: error.issues },
        "request validation failed",
      );
      return reply.status(400).send({
        error: {
          code: "BAD_REQUEST",
          message: "请求参数不合法。",
          issues: error.issues,
        },
      });
    }
    request.log.error(
      { err: error, request: requestLogContext(request) },
      "unhandled request error",
    );
    return reply.status(500).send({
      error: {
        code: "INTERNAL_ERROR",
        message: "服务端内部错误。",
      },
    });
  });

  const healthHandler = async () => ({ ok: true });
  app.get("/health", healthHandler);
  app.get("/api/health", healthHandler);

  app.get("/api/categories", async () => ({
    items: contentCategories.map(({ id, title, description }) => ({
      id,
      title,
      description,
    })),
  }));

  app.get("/api/categories/:id/videos", async (request) => {
    const params = categoryParamsSchema.parse(request.params);
    const query = pageQuerySchema.parse(request.query);
    const category = findContentCategory(params.id);
    if (!category) {
      throw new ApiError("NOT_FOUND", "不支持的内容分类。", 404);
    }
    const result = await categoryCache.getPage(
      category,
      query.page,
      query.pageSize,
    );
    return rewriteUrls(result, request);
  });

  app.get("/api/search", async (request) => {
    const query = searchSchema.parse(request.query);
    const result = await aggregator.search(query.q, query.page, query.pageSize);
    return rewriteUrls(result, request);
  });

  app.get("/api/videos/:id", async (request) => {
    const params = videoParamsSchema.parse(request.params);
    const result = await aggregator.getDetails(decodeURIComponent(params.id));
    return rewriteUrls(result, request);
  });

  app.get("/api/videos/:id/playback", async (request) => {
    const params = videoParamsSchema.parse(request.params);
    const result = {
      items: await aggregator.getPlaybackOptions(decodeURIComponent(params.id)),
    };
    return rewriteUrls(result, request);
  });

  app.get("/api/manifest/dash", async (request, reply) => {
    const { payload } = dashManifestPayloadFromQuery(
      dashManifestQuerySchema.parse(request.query),
    );
    let manifest;
    try {
      manifest = parseDashManifestPayload(payload);
    } catch (error) {
      throw new ApiError("BAD_REQUEST", "播放清单参数不合法。", 400, error);
    }
    await Promise.all([
      validateAssetProxyTarget(manifest.video.url),
      manifest.audio ? validateAssetProxyTarget(manifest.audio.url) : undefined,
    ]);
    reply.header("Cache-Control", "private, max-age=300");
    reply.type(DASH_MANIFEST_MIME_TYPE);
    return renderDashMpd(manifest, (rawUrl, role) =>
      wrapAssetUrl(publicApiBase(request), rawUrl, role),
    );
  });

  /**
   * 资源代理端点。
   * - 视频点播地址按 HTTP Range 直接转发给上游,播放器可边下边播和 seek
   * - 不等待完整文件下载完成,也不把 App 暴露给上游 CDN 细节
   * - 图片同样通过流式转发返回
   */
  app.get("/api/asset", async (request, reply) => {
    const url = assetUrlFromQuery(assetQuerySchema.parse(request.query));
    const target = await validateAssetProxyTarget(url);
    const range =
      typeof request.headers.range === "string"
        ? request.headers.range
        : undefined;
    const cacheKey = responseCache.keyFor(url, range);
    const cached = await responseCache.get(cacheKey);
    if (cached) {
      reply.status(cached.status);
      for (const [name, value] of Object.entries(cached.headers)) {
        reply.header(name, value);
      }
      reply.header("X-Cache", "HIT");
      logAssetProxyResponse(request, target, range, "hit", cached.status, {
        "content-length": cached.headers["content-length"],
        "content-range": cached.headers["content-range"],
        "content-type": cached.headers["content-type"],
      });
      logPrematureAssetClose(request, reply, target, range, cached.stream);
      return reply.send(cached.stream);
    }

    let upstream: Response;
    try {
      upstream = await fetchAssetTarget(target, range);
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "无法连接上游点播媒体地址。",
        502,
        error,
      );
    }
    if (!upstream.body) {
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        `上游点播媒体地址没有响应体: ${upstream.status}`,
        502,
      );
    }

    const responseHeaders = responseHeadersForCache(upstream);
    reply.status(upstream.status);
    for (const [name, value] of Object.entries(responseHeaders)) {
      reply.header(name, value);
    }
    reply.header("Cache-Control", "public, max-age=3600");
    const upstreamStream = Readable.fromWeb(upstream.body as never);
    const contentLength = numberHeader(upstream.headers.get("content-length"));
    if (responseCache.shouldCache(upstream.status, range, contentLength)) {
      reply.header("X-Cache", "MISS");
      const stream = responseCache.streamAndCache(cacheKey, upstreamStream, {
        status: upstream.status,
        headers: responseHeaders,
        cachedAt: Date.now(),
      });
      logAssetProxyResponse(request, target, range, "miss", upstream.status, {
        "content-length": upstream.headers.get("content-length"),
        "content-range": upstream.headers.get("content-range"),
        "content-type": upstream.headers.get("content-type"),
      });
      logPrematureAssetClose(request, reply, target, range, stream);
      return reply.send(stream);
    }

    reply.header("X-Cache", "BYPASS");
    logAssetProxyResponse(request, target, range, "bypass", upstream.status, {
      "content-length": upstream.headers.get("content-length"),
      "content-range": upstream.headers.get("content-range"),
      "content-type": upstream.headers.get("content-type"),
    });
    logPrematureAssetClose(request, reply, target, range, upstreamStream);
    return reply.send(upstreamStream);
  });

  return { app, categoryCache };
}

function isHealthCheckRequest(request: FastifyRequest): boolean {
  const path = requestPath(request.url);
  return path === "/health" || path === "/api/health";
}

function requestLogContext(request: FastifyRequest): Record<string, unknown> {
  const parsed = parseRequestUrl(request.url);
  const path = parsed?.pathname ?? requestPath(request.url);
  const context: Record<string, unknown> = {
    method: request.method,
    path,
    route: request.routeOptions.url,
    clientIp: request.ip,
    host: request.host,
  };
  const range = headerValue(request.headers.range);
  if (range) {
    context.range = range;
  }
  if (parsed) {
    const query = queryLogContext(parsed.searchParams);
    if (Object.keys(query).length > 0) {
      context.query = query;
    }
    if (path === "/api/asset") {
      const rawUrl = parsed.searchParams.get("url");
      context.asset = rawUrl
        ? assetTargetLogContext(rawUrl)
        : { opaqueRef: parsed.searchParams.has("ref") };
      context.hasAssetReference = parsed.searchParams.has("ref");
      context.hasAssetSignature = parsed.searchParams.has("sig");
    }
  }
  return context;
}

function responseLogContext(reply: FastifyReply): Record<string, unknown> {
  return { statusCode: reply.statusCode };
}

function logApiError(request: FastifyRequest, error: ApiError): void {
  const payload = {
    request: requestLogContext(request),
    error: {
      code: error.code,
      message: error.message,
      statusCode: error.statusCode,
      upstreamError: summarizeUpstreamError(error.upstreamError),
    },
  };
  if (error.statusCode >= 500) {
    request.log.error(payload, "api request failed");
    return;
  }
  request.log.warn(payload, "api request failed");
}

function publicErrorMessage(error: ApiError): string {
  switch (error.code) {
    case "UPSTREAM_TIMEOUT":
      return "上游来源响应超时。";
    case "UPSTREAM_UNAVAILABLE":
      return "上游来源暂不可用。";
    default:
      return error.message;
  }
}

function logAssetProxyResponse(
  request: FastifyRequest,
  target: URL,
  range: string | undefined,
  cache: "hit" | "miss" | "bypass",
  status: number,
  headers: Record<string, string | undefined | null>,
): void {
  const payload = {
    asset: {
      ...assetTargetLogContext(target.toString()),
      range,
      cache,
      upstreamStatus: status,
      upstreamHeaders: compactHeaders(headers),
    },
  };
  if (status >= 400) {
    request.log.warn(payload, "asset proxy upstream returned error");
    return;
  }
  request.log.info(payload, "asset proxy upstream response");
}

function logPrematureAssetClose(
  request: FastifyRequest,
  reply: FastifyReply,
  target: URL,
  range: string | undefined,
  stream: NodeJS.ReadableStream,
): void {
  let streamEnded = false;
  stream.once("end", () => {
    streamEnded = true;
  });
  stream.once("error", (error) => {
    request.log.warn(
      {
        err: error,
        asset: {
          ...assetTargetLogContext(target.toString()),
          range,
        },
      },
      "asset proxy stream error",
    );
  });
  reply.raw.once("close", () => {
    if (streamEnded || reply.raw.writableEnded) {
      return;
    }
    request.log.warn(
      {
        asset: {
          ...assetTargetLogContext(target.toString()),
          range,
        },
        statusCode: reply.statusCode,
      },
      "asset proxy client disconnected before stream ended",
    );
  });
}

function elapsedMs(startedAt: bigint | undefined): number | undefined {
  if (!startedAt) {
    return undefined;
  }
  return Number(process.hrtime.bigint() - startedAt) / 1_000_000;
}

function parseRequestUrl(url: string): URL | undefined {
  try {
    return new URL(url, "http://internal");
  } catch {
    return undefined;
  }
}

function requestPath(url: string): string {
  return url.split("?", 1)[0] || url;
}

function queryLogContext(params: URLSearchParams): Record<string, string> {
  const query: Record<string, string> = {};
  for (const [key, value] of params.entries()) {
    if (key === "url" || key === "ref" || key === "sig" || key === "payload") {
      continue;
    }
    query[key] = truncateMiddle(value, 160);
  }
  return query;
}

function assetTargetLogContext(rawUrl: string | null): Record<string, unknown> {
  if (!rawUrl) {
    return { missing: true };
  }
  try {
    const parsed = new URL(rawUrl);
    return {
      scheme: parsed.protocol.replace(/:$/, ""),
      host: parsed.host,
      path: truncateMiddle(parsed.pathname, 120),
      provider: assetProvider(parsed.hostname),
    };
  } catch {
    return { invalid: true, value: truncateMiddle(rawUrl, 120) };
  }
}

function assetProvider(hostname: string): string {
  if (hostname.endsWith(".archive.org")) {
    return "internet-archive";
  }
  if (
    hostname.endsWith(".bilivideo.com") ||
    hostname.endsWith(".bilibili.com")
  ) {
    return "bilibili";
  }
  if (
    hostname.endsWith(".googlevideo.com") ||
    hostname.endsWith(".youtube.com")
  ) {
    return "youtube";
  }
  return "unknown";
}

function buildProviders(sources: MediaSourceConfig[]): VideoProvider[] {
  const providers = sources
    .filter((source) => source.enabled)
    .map((source) => providerForSource(source.id));
  if (providers.length === 0) {
    throw new Error("At least one media provider must be enabled");
  }
  return providers;
}

function providerForSource(id: ProviderName): VideoProvider {
  switch (id) {
    case "internet-archive":
      return new InternetArchiveProvider();
    case "youtube":
      return new YouTubeProvider();
    case "bilibili":
      return new BilibiliProvider();
  }
}

function compactHeaders(
  headers: Record<string, string | undefined | null>,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [name, value] of Object.entries(headers)) {
    if (value) {
      out[name] = value;
    }
  }
  return out;
}

function headerValue(value: string | string[] | undefined): string | undefined {
  if (Array.isArray(value)) {
    return value.join(", ");
  }
  return value;
}

function summarizeUpstreamError(value: unknown): unknown {
  if (!value) {
    return undefined;
  }
  if (value instanceof Error) {
    return {
      name: value.name,
      message: value.message,
    };
  }
  if (typeof value === "string") {
    return truncateMiddle(value, 500);
  }
  if (typeof value !== "object") {
    return value;
  }
  const record = value as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(record)) {
    if (key === "body" && typeof item === "string") {
      out[key] = parseJsonLogValue(item) ?? truncateMiddle(item, 500);
    } else if (item instanceof Error) {
      out[key] = { name: item.name, message: item.message };
    } else if (typeof item === "string") {
      out[key] = truncateMiddle(item, 500);
    } else {
      out[key] = item;
    }
  }
  return out;
}

function parseJsonLogValue(value: string): unknown {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return undefined;
  }
}

function truncateMiddle(value: string, maxLength: number): string {
  if (value.length <= maxLength) {
    return value;
  }
  const headLength = Math.ceil((maxLength - 3) / 2);
  const tailLength = Math.floor((maxLength - 3) / 2);
  return `${value.slice(0, headLength)}...${value.slice(-tailLength)}`;
}

async function fetchAssetTarget(
  target: URL,
  range: string | string[] | undefined,
): Promise<Response> {
  let current = target;
  for (
    let redirectCount = 0;
    redirectCount <= MAX_ASSET_REDIRECTS;
    redirectCount += 1
  ) {
    const response = await fetchWithTimeout(fetch, current, {
      redirect: "manual",
      headers: upstreamRequestHeaders(current, range),
    });
    if (!isRedirectResponse(response.status)) {
      return response;
    }
    const location = response.headers.get("location");
    if (!location) {
      return response;
    }
    current = await validateAssetProxyTarget(
      new URL(location, current).toString(),
    );
  }
  throw new ApiError(
    "UPSTREAM_UNAVAILABLE",
    "点播媒体地址重定向次数过多。",
    502,
  );
}

function isRedirectResponse(status: number): boolean {
  return status >= 300 && status < 400;
}

function upstreamRequestHeaders(
  url: URL,
  range: string | string[] | undefined,
): Record<string, string> {
  const headers: Record<string, string> = {
    "User-Agent":
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  };
  if (typeof range === "string") {
    headers.Range = range;
  }
  const host = url.hostname;
  if (host.endsWith(".bilivideo.com") || host.endsWith(".bilibili.com")) {
    headers.Referer = "https://www.bilibili.com";
  } else if (
    host.endsWith(".googlevideo.com") ||
    host.endsWith(".youtube.com")
  ) {
    headers.Referer = "https://www.youtube.com";
  }
  return headers;
}

function responseHeadersForCache(upstream: Response): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const name of [
    "content-type",
    "content-length",
    "content-range",
    "accept-ranges",
  ]) {
    const value = upstream.headers.get(name);
    if (value) {
      headers[name] = value;
    }
  }
  headers["accept-ranges"] = headers["accept-ranges"] ?? "bytes";
  return headers;
}

function numberHeader(value: string | null): number | undefined {
  if (!value) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/** 把响应里的上游 url(thumbnailUrl / 点播 url)重写为本服务的代理 URL。 */
function rewriteUrls<T>(payload: T, request: FastifyRequest): T {
  return walk(payload, publicApiBase(request)) as T;
}

function publicApiBase(request: FastifyRequest): string {
  return `${request.protocol}://${request.host}${normalizePublicApiPrefix(request.headers["x-forwarded-prefix"])}`;
}

const DEFAULT_PUBLIC_API_PREFIX = "/api";
const PUBLIC_API_PREFIX_PATTERN = /^\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$/;

export function normalizePublicApiPrefix(
  value: string | string[] | undefined,
): string {
  const rawValue = Array.isArray(value) ? value[0] : value;
  const candidate = rawValue?.split(",")[0]?.trim();
  if (!candidate) {
    return DEFAULT_PUBLIC_API_PREFIX;
  }
  const prefixed = candidate.startsWith("/") ? candidate : `/${candidate}`;
  const normalized = prefixed.replace(/\/+$/, "");
  if (
    !normalized ||
    normalized.includes("//") ||
    !PUBLIC_API_PREFIX_PATTERN.test(normalized)
  ) {
    return DEFAULT_PUBLIC_API_PREFIX;
  }
  return normalized;
}

function shouldProxy(url: string): boolean {
  // 只代理 http(s) 资源,跳过相对路径 / data: / blob:。
  return /^https?:\/\//i.test(url);
}

function walk(value: unknown, apiBase: string): unknown {
  if (Array.isArray(value)) {
    return value.map((v) => walk(v, apiBase));
  }
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (
        (k === "thumbnailUrl" || k === "url") &&
        typeof v === "string" &&
        shouldProxy(v)
      ) {
        out[k] = wrapUrl(apiBase, v);
      } else if (
        k === "url" &&
        typeof v === "string" &&
        isDashManifestReference(v)
      ) {
        out[k] = wrapDashManifestUrl(apiBase, v);
      } else if (k === "sourceUrl") {
        // sourceUrl 是 Provider 内部详情页,不作为公开播放地址,不走代理。
        out[k] = v;
      } else {
        out[k] = walk(v, apiBase);
      }
    }
    return out;
  }
  return value;
}

export function wrapAssetUrl(
  apiBase: string,
  original: string,
  role?: DashMediaUrlRole,
): string {
  const ref = assetReference(original, role);
  return `${apiBase}/asset?ref=${encodeURIComponent(ref)}&sig=${assetReferenceSignature(ref)}`;
}

function wrapUrl(apiBase: string, original: string): string {
  return wrapAssetUrl(apiBase, original);
}

export function wrapDashManifestUrl(
  apiBase: string,
  reference: string,
): string {
  const payload = dashManifestPayloadFromReference(reference);
  const ref = dashManifestReference(payload);
  return `${apiBase}/manifest/dash?ref=${encodeURIComponent(ref)}&sig=${dashManifestReferenceSignature(ref)}`;
}

function assetReference(url: string, role?: DashMediaUrlRole): string {
  return opaqueReference(
    {
      v: 1,
      kind: "asset",
      url,
      ...(role ? { role } : {}),
    } satisfies AssetReferencePayload,
  );
}

function dashManifestReference(payload: string): string {
  return opaqueReference(
    {
      v: 1,
      kind: "dash-manifest",
      payload,
    } satisfies DashManifestReferencePayload,
  );
}

function opaqueReference(payload: unknown): string {
  const encodedPayload = JSON.stringify(payload);
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", ASSET_REFERENCE_KEY, iv);
  const encrypted = Buffer.concat([
    cipher.update(encodedPayload, "utf8"),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return [
    "v1",
    iv.toString("base64url"),
    encrypted.toString("base64url"),
    tag.toString("base64url"),
  ].join(".");
}

function assetReferenceSignature(ref: string): string {
  return createHmac("sha256", ASSET_SIGNING_KEY)
    .update(`asset-reference:${ref}`)
    .digest("hex");
}

function dashManifestReferenceSignature(ref: string): string {
  return createHmac("sha256", ASSET_SIGNING_KEY)
    .update(`dash-manifest-reference:${ref}`)
    .digest("hex");
}

function verifyAssetReferenceSignature(ref: string, sig: string): boolean {
  const expected = Buffer.from(assetReferenceSignature(ref), "hex");
  const actual = Buffer.from(sig, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function verifyDashManifestReferenceSignature(ref: string, sig: string): boolean {
  const expected = Buffer.from(dashManifestReferenceSignature(ref), "hex");
  const actual = Buffer.from(sig, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function assetUrlFromQuery(query: z.infer<typeof assetQuerySchema>): string {
  if (!verifyAssetReferenceSignature(query.ref, query.sig)) {
    throw new ApiError("BAD_REQUEST", "资源代理签名无效。", 400);
  }
  return parseAssetReference(query.ref).url;
}

function dashManifestPayloadFromQuery(
  query: z.infer<typeof dashManifestQuerySchema>,
): DashManifestReferencePayload {
  if (!verifyDashManifestReferenceSignature(query.ref, query.sig)) {
    throw new ApiError("BAD_REQUEST", "播放清单签名无效。", 400);
  }
  return dashManifestReferencePayloadSchema.parse(
    parseOpaqueReference(query.ref, "播放清单引用无效。"),
  );
}

function parseAssetReference(ref: string): AssetReferencePayload {
  return assetReferencePayloadSchema.parse(
    parseOpaqueReference(ref, "资源代理引用无效。"),
  );
}

function parseOpaqueReference(ref: string, invalidMessage: string): unknown {
  const parts = ref.split(".");
  if (parts.length !== 4 || parts[0] !== "v1") {
    throw new ApiError("BAD_REQUEST", invalidMessage, 400);
  }
  try {
    const iv = Buffer.from(parts[1], "base64url");
    const encrypted = Buffer.from(parts[2], "base64url");
    const tag = Buffer.from(parts[3], "base64url");
    if (iv.length !== 12 || tag.length !== 16) {
      throw new Error("invalid asset reference shape");
    }
    const decipher = createDecipheriv("aes-256-gcm", ASSET_REFERENCE_KEY, iv);
    decipher.setAuthTag(tag);
    const decoded = Buffer.concat([
      decipher.update(encrypted),
      decipher.final(),
    ]).toString("utf8");
    return JSON.parse(decoded) as unknown;
  } catch (error) {
    throw new ApiError("BAD_REQUEST", invalidMessage, 400, error);
  }
}

async function validateAssetProxyTarget(rawUrl: string): Promise<URL> {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new ApiError("BAD_REQUEST", "资源地址不合法。", 400);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new ApiError("BAD_REQUEST", "资源代理只支持 HTTP/HTTPS 地址。", 400);
  }
  const hostname = hostnameForChecks(parsed.hostname);
  if (isBlockedHostname(hostname)) {
    throw new ApiError(
      "BAD_REQUEST",
      "资源地址不允许访问本地或内网目标。",
      400,
    );
  }
  if (isBlockedAddress(hostname)) {
    throw new ApiError(
      "BAD_REQUEST",
      "资源地址不允许访问本地或内网目标。",
      400,
    );
  }
  let addresses: { address: string }[];
  try {
    addresses = await lookup(hostname, { all: true, verbatim: true });
  } catch (error) {
    throw new ApiError(
      "UPSTREAM_UNAVAILABLE",
      "资源地址无法解析。",
      502,
      error,
    );
  }
  if (
    addresses.length === 0 ||
    addresses.some(({ address }) => isBlockedAddress(address))
  ) {
    throw new ApiError(
      "BAD_REQUEST",
      "资源地址不允许访问本地或内网目标。",
      400,
    );
  }
  return parsed;
}

function hostnameForChecks(hostname: string): string {
  return hostname.replace(/^\[(.*)]$/, "$1");
}

function isBlockedHostname(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/\.$/, "");
  return (
    host === "localhost" ||
    host.endsWith(".localhost") ||
    host.endsWith(".local") ||
    host === "metadata.google.internal"
  );
}

function isBlockedAddress(address: string): boolean {
  const ipVersion = net.isIP(address);
  if (ipVersion === 4) {
    return isBlockedIPv4(address);
  }
  if (ipVersion === 6) {
    return isBlockedIPv6(address);
  }
  return false;
}

function isBlockedIPv4(address: string): boolean {
  const parts = address.split(".").map((part) => Number(part));
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part))) {
    return true;
  }
  const [a, b] = parts;
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19)) ||
    a >= 224
  );
}

function isBlockedIPv6(address: string): boolean {
  const normalized = address.toLowerCase();
  const mappedIPv4 = normalized.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
  if (mappedIPv4) {
    return isBlockedIPv4(mappedIPv4[1]);
  }
  const mappedHexIPv4 = normalized.match(
    /^(?:::ffff:|0:0:0:0:0:ffff:)([0-9a-f]{1,4}):([0-9a-f]{1,4})$/,
  );
  if (mappedHexIPv4) {
    const high = Number.parseInt(mappedHexIPv4[1], 16);
    const low = Number.parseInt(mappedHexIPv4[2], 16);
    const mappedAddress = [
      (high >> 8) & 0xff,
      high & 0xff,
      (low >> 8) & 0xff,
      low & 0xff,
    ].join(".");
    return isBlockedIPv4(mappedAddress);
  }
  return (
    normalized === "::" ||
    normalized === "::1" ||
    normalized.startsWith("fc") ||
    normalized.startsWith("fd") ||
    normalized.startsWith("fe80:") ||
    normalized.startsWith("ff")
  );
}
