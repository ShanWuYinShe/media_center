import cors from "@fastify/cors";
import Fastify, { type FastifyRequest } from "fastify";
import { Readable } from "node:stream";
import { z } from "zod";
import { contentCategories, findContentCategory } from "./contentCategories.js";
import { ApiError } from "./errors.js";
import { BilibiliProvider } from "./providers/BilibiliProvider.js";
import { YouTubeProvider } from "./providers/YouTubeProvider.js";
import type { VideoProvider } from "./providers/VideoProvider.js";
import { CategoryResultCache } from "./services/CategoryResultCache.js";
import { HttpResponseCache } from "./services/HttpResponseCache.js";
import { VideoAggregator } from "./services/VideoAggregator.js";
import { fetchWithTimeout } from "./services/fetchWithTimeout.js";

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
const assetQuerySchema = z.object({ url: z.string().url() });

export function buildServer() {
  const app = Fastify({ logger: true, trustProxy: true });
  const providers: VideoProvider[] = [
    new BilibiliProvider(),
    new YouTubeProvider(),
  ];
  const aggregator = new VideoAggregator(providers);
  const categoryCache = new CategoryResultCache(aggregator, contentCategories);
  const responseCache = new HttpResponseCache();

  app.register(cors, { origin: true });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      return reply.status(error.statusCode).send({
        error: {
          code: error.code,
          message: error.message,
        },
      });
    }
    if (error instanceof z.ZodError) {
      return reply.status(400).send({
        error: {
          code: "BAD_REQUEST",
          message: "请求参数不合法。",
          issues: error.issues,
        },
      });
    }
    request.log.error(error);
    return reply.status(500).send({
      error: {
        code: "INTERNAL_ERROR",
        message: "服务端内部错误。",
      },
    });
  });

  app.get("/health", async () => ({ ok: true }));

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

  /**
   * 资源代理端点。
   * - 视频点播地址按 HTTP Range 直接转发给上游,播放器可边下边播和 seek
   * - 不等待完整文件下载完成,也不把 App 暴露给 Bilibili/YouTube CDN 细节
   * - 图片同样通过流式转发返回
   */
  app.get("/api/asset", async (request, reply) => {
    const { url } = assetQuerySchema.parse(request.query);
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
      return reply.send(cached.stream);
    }

    const upstreamHeaders = upstreamRequestHeaders(url, range);
    let upstream: Response;
    try {
      upstream = await fetchWithTimeout(fetch, url, {
        redirect: "follow",
        headers: upstreamHeaders,
      });
    } catch (error) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "无法连接上游点播媒体地址。",
        502,
        error,
      );
    }
    if (!upstream.body) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
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
      return reply.send(
        responseCache.streamAndCache(cacheKey, upstreamStream, {
          status: upstream.status,
          headers: responseHeaders,
          cachedAt: Date.now(),
        }),
      );
    }

    reply.header("X-Cache", "BYPASS");
    return reply.send(upstreamStream);
  });

  return { app, categoryCache };
}

function upstreamRequestHeaders(
  url: string,
  range: string | string[] | undefined,
): Record<string, string> {
  const headers: Record<string, string> = {
    "User-Agent":
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  };
  if (typeof range === "string") {
    headers.Range = range;
  }
  const host = new URL(url).hostname;
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
  const apiBase = `${request.protocol}://${request.host}${normalizePublicApiPrefix(request.headers["x-forwarded-prefix"])}`;
  return walk(payload, apiBase) as T;
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

function wrapUrl(apiBase: string, original: string): string {
  return `${apiBase}/asset?url=${encodeURIComponent(original)}`;
}
