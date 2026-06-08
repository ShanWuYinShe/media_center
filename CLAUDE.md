# CLAUDE.md

合规公开视频媒体中心：TypeScript 服务端聚合 Bilibili/YouTube，Android TV 客户端播放。通用约束见全局 `~/.claude/CLAUDE.md`。

## 仓库结构

- `media_server/` — TypeScript + Fastify 后端
- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端

## media_server

```bash
bun run dev       # 开发热重载（tsx watch）
bun run build     # 构建
bun start         # 生产运行
bun test          # 运行测试（vitest / bun test，按项目脚本为准）
bun run lint      # 类型检查（tsc --noEmit）
bun run format    # 格式化（prettier）
```

- **搜索聚合**：`VideoAggregator` 并行查询所有 provider（`Promise.allSettled`，单个失败不阻塞），详情结果带 LRU 内存缓存。分类列表由服务端分类缓存定期维护，关键词搜索接口不缓存响应。新增 provider 实现 `VideoProvider` 接口并注册到 `buildServer()` 的 providers 数组即可。
- **错误处理**：业务错误抛 `ApiError`，参数校验用 zod schema，全局 error handler 统一响应 `{ error: { code, message } }`。
- **内容分类**：数据在 `src/contentCategories.json`，新增分类编辑此文件。

## media_app

```bash
./gradlew :app:assembleDebug                                 # 编译调试 APK
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=http://ip:8787  # 真机/盒子编译
```

- **技术栈**：Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。

## API 约定

- `/api/categories` — 分类列表
- `/api/categories/:id/videos` — 分类视频列表（服务端定期缓存，默认缓存 100 页，超出请求按需扩展）
- `/api/search` — 关键词搜索（不缓存响应，先校验点播地址，只返回可播放结果）
- `/api/videos/:id` — 视频详情
- `/api/videos/:id/playback` — 点播播放地址
- 公开 API 不暴露具体视频来源，客户端无需感知内容来自哪个 Provider

## 约束

- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **播放代理**：通过 `/api/asset` 做 Range 代理，支持边下边播和 seek。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **新来源**：通过继承 `VideoProvider` 接口扩展，使用官方 API 或合规开源工具。
- **服务端地址**：模拟器默认 `http://10.0.2.2:8787`，真机用 `-PMEDIA_SERVER_URL` 覆盖。
- **缓存**：后端按 URL + Range 缓存点播片段，默认目录 `/cache`，上限 50GB。
- **yt-dlp**：YouTube 点播解析依赖可选的 `yt-dlp`，通过 `VOD_EXTRACTOR_BIN` 环境变量指定。
