import { expect, test } from "vitest";
import { normalizePublicApiPrefix } from "../src/server.js";

test("公开 API 前缀默认使用内部 /api", () => {
  expect(normalizePublicApiPrefix(undefined)).toBe("/api");
  expect(normalizePublicApiPrefix("")).toBe("/api");
  expect(normalizePublicApiPrefix("/")).toBe("/api");
});

test("公开 API 前缀支持 nginx 转发前缀", () => {
  expect(normalizePublicApiPrefix("/api/media")).toBe("/api/media");
  expect(normalizePublicApiPrefix("api/media/")).toBe("/api/media");
  expect(normalizePublicApiPrefix("/api/media, /ignored")).toBe("/api/media");
  expect(normalizePublicApiPrefix(["/api/media", "/ignored"])).toBe(
    "/api/media",
  );
});

test("公开 API 前缀拒绝非路径值", () => {
  expect(normalizePublicApiPrefix("https://example.test/api")).toBe("/api");
  expect(normalizePublicApiPrefix("/api/media?bad=1")).toBe("/api");
});
