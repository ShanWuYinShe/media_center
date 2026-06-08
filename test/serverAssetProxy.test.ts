import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, expect, test } from "vitest";

const cacheDir = mkdtempSync(join(tmpdir(), "media-server-cache-"));
process.env.BACKEND_CACHE_DIR = cacheDir;
process.env.MEDIA_ASSET_SIGNING_KEY = "test-signing-key";
const { buildServer, wrapAssetUrl, wrapDashManifestUrl } =
  await import("../src/server.js");
const { createDashManifestReference } =
  await import("../src/services/StreamManifests.js");
const { app } = buildServer();

afterAll(async () => {
  await app.close();
  rmSync(cacheDir, { recursive: true, force: true });
  delete process.env.BACKEND_CACHE_DIR;
  delete process.env.MEDIA_ASSET_SIGNING_KEY;
});

test("asset 代理拒绝未签名请求", async () => {
  const response = await app.inject({
    method: "GET",
    url: "/api/asset?url=https%3A%2F%2Fexample.com%2Fvideo.mp4",
  });

  expect(response.statusCode).toBe(400);
});

test("asset 代理拒绝签名不匹配请求", async () => {
  const response = await app.inject({
    method: "GET",
    url: `/api/asset?url=https%3A%2F%2Fexample.com%2Fvideo.mp4&sig=${"0".repeat(64)}`,
  });

  expect(response.statusCode).toBe(400);
});

test.each(["http://127.0.0.1/private", "http://[::1]/private"])(
  "asset 代理拒绝本地目标 %s",
  async (target) => {
    const response = await app.inject({
      method: "GET",
      url: wrapAssetUrl("/api", target),
    });

    expect(response.statusCode).toBe(400);
  },
);

test("asset 代理拒绝 IPv4-mapped IPv6 本地目标", async () => {
  const response = await app.inject({
    method: "GET",
    url: wrapAssetUrl("/api", "http://[::ffff:7f00:1]/private"),
  });

  expect(response.statusCode).toBe(400);
});

test("asset 代理拒绝重定向到本地目标", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    Response.redirect("http://127.0.0.1/private", 302);
  try {
    const response = await app.inject({
      method: "GET",
      url: wrapAssetUrl("/api", "https://example.com/video.mp4"),
    });

    expect(response.statusCode).toBe(400);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("DASH 清单端点返回签名 asset URL", async () => {
  const manifest = createDashManifestReference({
    version: 1,
    durationSeconds: 120,
    video: {
      id: "video-720p",
      url: "https://example.com/video.m4s",
      mimeType: "video/mp4",
      codecs: "avc1.64001f",
      bandwidth: 800000,
      width: 1280,
      height: 720,
      initialization: "0-1000",
      indexRange: "1001-1200",
    },
    audio: {
      id: "audio",
      url: "https://example.com/audio.m4s",
      mimeType: "audio/mp4",
      codecs: "mp4a.40.2",
      bandwidth: 128000,
      initialization: "0-800",
      indexRange: "801-900",
    },
  });

  const response = await app.inject({
    method: "GET",
    url: wrapDashManifestUrl("/api", manifest),
  });

  expect(response.statusCode).toBe(200);
  expect(response.headers["content-type"]).toContain("application/dash+xml");
  expect(response.body).toContain("<MPD");
  expect(response.body).toContain(
    "/api/asset?url=https%3A%2F%2Fexample.com%2Fvideo.m4s",
  );
  expect(response.body).toContain(
    "/api/asset?url=https%3A%2F%2Fexample.com%2Faudio.m4s",
  );
  expect(response.body).toContain('<SegmentBase indexRange="1001-1200">');
});
