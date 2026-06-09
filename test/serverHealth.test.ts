import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, expect, test } from "vitest";

const cacheDir = mkdtempSync(join(tmpdir(), "media-server-cache-"));
process.env.BACKEND_CACHE_DIR = cacheDir;
const { buildServer } = await import("../src/server.js");
const { app } = buildServer();

afterAll(async () => {
  await app.close();
  rmSync(cacheDir, { recursive: true, force: true });
  delete process.env.BACKEND_CACHE_DIR;
});

test.each(["/health", "/api/health"])("%s 返回服务健康状态", async (path) => {
  const response = await app.inject({ method: "GET", url: path });

  expect(response.statusCode).toBe(200);
  expect(response.json()).toEqual({ ok: true });
});

test("视频详情路由接受较长 opaque 视频 ID", async () => {
  const longOpaqueId = `v1.${"a".repeat(120)}.${"b".repeat(120)}.${"c".repeat(32)}`;
  const response = await app.inject({
    method: "GET",
    url: `/api/videos/${longOpaqueId}`,
  });

  expect(response.statusCode).toBe(400);
  expect(response.json()).toMatchObject({
    error: { code: "BAD_REQUEST" },
  });
});
