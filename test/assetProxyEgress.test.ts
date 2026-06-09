import { describe, expect, test } from "vitest";

process.env.MEDIA_ASSET_SIGNING_KEY ??= "test-signing-key";
const {
  DEFAULT_ASSET_PROXY_HOST_SUFFIXES,
  parseAssetProxyHostSuffixes,
  hostNeedsUpstreamProxy,
} = await import("../src/server.js");

describe("parseAssetProxyHostSuffixes", () => {
  test("未配置时回退到 Bilibili 媒体 CDN 默认后缀", () => {
    expect(parseAssetProxyHostSuffixes(undefined)).toEqual(
      DEFAULT_ASSET_PROXY_HOST_SUFFIXES,
    );
    expect(parseAssetProxyHostSuffixes("   ")).toEqual(
      DEFAULT_ASSET_PROXY_HOST_SUFFIXES,
    );
  });

  test("按逗号拆分并规范化大小写/空白", () => {
    expect(
      parseAssetProxyHostSuffixes(" .Bilivideo.com , Akamaized.net "),
    ).toEqual([".bilivideo.com", "akamaized.net"]);
  });
});

describe("hostNeedsUpstreamProxy", () => {
  const suffixes = [".bilivideo.com", ".bilivideo.cn"];

  test("命中带前导点的后缀(含多级子域)", () => {
    expect(
      hostNeedsUpstreamProxy("upos-sz-mirrorcos.bilivideo.com", suffixes),
    ).toBe(true);
    expect(hostNeedsUpstreamProxy("bilivideo.com", suffixes)).toBe(true);
    expect(
      hostNeedsUpstreamProxy("xy123.mcdn.bilivideo.cn", suffixes),
    ).toBe(true);
  });

  test("不命中无关 host(如图片 CDN)", () => {
    expect(hostNeedsUpstreamProxy("i0.hdslb.com", suffixes)).toBe(false);
    expect(hostNeedsUpstreamProxy("example.com", suffixes)).toBe(false);
    expect(
      hostNeedsUpstreamProxy("notbilivideo.com", suffixes),
    ).toBe(false);
  });

  test("不带前导点的后缀同时匹配裸域与子域", () => {
    const bare = ["bilivideo.com"];
    expect(hostNeedsUpstreamProxy("bilivideo.com", bare)).toBe(true);
    expect(hostNeedsUpstreamProxy("a.bilivideo.com", bare)).toBe(true);
    expect(hostNeedsUpstreamProxy("evilbilivideo.com", bare)).toBe(false);
  });
});
