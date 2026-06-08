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
