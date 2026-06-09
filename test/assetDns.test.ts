import { expect, test } from "vitest";
import {
  AssetDnsResolver,
  BlockedAddressError,
  type LookupFn,
  type ResolvedAddress,
} from "../src/services/assetDns.js";

const publicAddress: ResolvedAddress[] = [{ address: "93.184.216.34", family: 4 }];
const noBlock = () => false;
const immediateSleep = async () => undefined;

test("同一域名并发解析只触发一次底层 lookup(single-flight)", async () => {
  let calls = 0;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    await immediateSleep();
    return publicAddress;
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: noBlock,
    lookupFn,
    sleepFn: immediateSleep,
  });

  const results = await Promise.all(
    Array.from({ length: 40 }, () => resolver.resolve("example.com")),
  );

  expect(calls).toBe(1);
  for (const result of results) {
    expect(result).toEqual(publicAddress);
  }
});

test("命中缓存的解析不再触发底层 lookup", async () => {
  let calls = 0;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    return publicAddress;
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: noBlock,
    lookupFn,
    sleepFn: immediateSleep,
  });

  await resolver.resolve("example.com");
  await resolver.resolve("EXAMPLE.com");
  await resolver.resolve("example.com");

  expect(calls).toBe(1);
});

test("缓存按 TTL 过期后重新解析", async () => {
  let calls = 0;
  let clock = 1_000;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    return publicAddress;
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: noBlock,
    ttlMs: 30_000,
    lookupFn,
    now: () => clock,
    sleepFn: immediateSleep,
  });

  await resolver.resolve("example.com");
  clock += 29_000;
  await resolver.resolve("example.com");
  expect(calls).toBe(1);

  clock += 2_000;
  await resolver.resolve("example.com");
  expect(calls).toBe(2);
});

test("瞬时 DNS 错误(EAI_AGAIN)退避重试后成功", async () => {
  let calls = 0;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    if (calls < 3) {
      throw Object.assign(new Error("temporary failure"), { code: "EAI_AGAIN" });
    }
    return publicAddress;
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: noBlock,
    maxRetries: 2,
    lookupFn,
    sleepFn: immediateSleep,
  });

  await expect(resolver.resolve("example.com")).resolves.toEqual(publicAddress);
  expect(calls).toBe(3);
});

test("确定性 DNS 错误(ENOTFOUND)不重试,直接抛出", async () => {
  let calls = 0;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    throw Object.assign(new Error("not found"), { code: "ENOTFOUND" });
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: noBlock,
    maxRetries: 2,
    lookupFn,
    sleepFn: immediateSleep,
  });

  await expect(resolver.resolve("nope.invalid")).rejects.toMatchObject({
    code: "ENOTFOUND",
  });
  expect(calls).toBe(1);
});

test("解析到内网地址时抛 BlockedAddressError 且不缓存", async () => {
  let calls = 0;
  const lookupFn: LookupFn = async () => {
    calls += 1;
    return [{ address: "10.0.0.5", family: 4 }];
  };
  const resolver = new AssetDnsResolver({
    isBlockedAddress: (address) => address.startsWith("10."),
    lookupFn,
    sleepFn: immediateSleep,
  });

  await expect(resolver.resolve("rebind.example")).rejects.toBeInstanceOf(
    BlockedAddressError,
  );
  await expect(resolver.resolve("rebind.example")).rejects.toBeInstanceOf(
    BlockedAddressError,
  );
  expect(calls).toBe(2);
});
