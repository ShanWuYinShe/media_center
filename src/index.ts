import { buildServer } from "./server.js";

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "0.0.0.0";
const { app, categoryCache } = buildServer();
const CATEGORY_REFRESH_INTERVAL_MS = Number(
  process.env.CATEGORY_REFRESH_INTERVAL_MS ?? 30 * 60_000,
);

await app.listen({ host, port });

const refreshCategoryCache = () =>
  categoryCache.refreshDefaultPages((category, error) => {
    app.log.warn(
      { err: error, categoryId: category.id },
      "分类视频缓存刷新失败",
    );
  });

// 分类浏览结果由服务端维护稳定快照:启动后立即后台刷新,之后定期更新。
void refreshCategoryCache();
setInterval(() => {
  void refreshCategoryCache();
}, CATEGORY_REFRESH_INTERVAL_MS).unref();
