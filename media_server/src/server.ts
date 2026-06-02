import cors from "@fastify/cors";
import Fastify from "fastify";
import { z } from "zod";
import { contentCategories, findContentCategory } from "./contentCategories.js";
import { ApiError } from "./errors.js";
import { InternetArchiveProvider } from "./providers/InternetArchiveProvider.js";
import { YouTubeProvider } from "./providers/YouTubeProvider.js";
import type { VideoProvider } from "./providers/VideoProvider.js";
import { VideoAggregator } from "./services/VideoAggregator.js";

const searchSchema = z.object({
  q: z.string().trim().max(120).optional().default(""),
  category: z.string().trim().max(80).optional(),
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(40).default(20),
});

const videoParamsSchema = z.object({ id: z.string().min(1) });

export function buildServer() {
  const app = Fastify({ logger: true });
  const providers: VideoProvider[] = [new InternetArchiveProvider()];
  if (process.env.YOUTUBE_API_KEY) {
    providers.push(new YouTubeProvider(process.env.YOUTUBE_API_KEY));
  }
  const aggregator = new VideoAggregator(providers);

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

  app.get("/api/categories", async () => ({ items: contentCategories }));

  app.get("/api/search", async (request) => {
    const query = searchSchema.parse(request.query);
    const category = findContentCategory(query.category);
    if (query.category && !category) {
      throw new ApiError("NOT_FOUND", "不支持的内容分类。", 404);
    }
    if (!query.q && !category) {
      throw new ApiError(
        "BAD_REQUEST",
        "关键词或内容分类至少需要提供一个。",
        400,
      );
    }
    return aggregator.search(query.q, query.page, query.pageSize, category);
  });

  app.get("/api/videos/:id", async (request) => {
    const params = videoParamsSchema.parse(request.params);
    return aggregator.getDetails(decodeURIComponent(params.id));
  });

  app.get("/api/videos/:id/playback", async (request) => {
    const params = videoParamsSchema.parse(request.params);
    return {
      items: await aggregator.getPlaybackOptions(decodeURIComponent(params.id)),
    };
  });

  return app;
}
