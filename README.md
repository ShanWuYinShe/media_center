# Media Center

这个仓库同时包含媒体中心的客户端与服务端：

- `media_app`：Android TV + Kotlin + Jetpack Compose + Media3 客户端，针对遥控器焦点、电视安全边距和 2 米观看距离优化。
- `media_server`：Go 实现的目录服务，提供 provider 列表、分类列表、视频元数据；youtube provider 的实时搜索与播放解析经 `vod_extractor` 代理（受控例外，结果不落 catalog）。
- `vod_extractor`：Python + yt-dlp 内部点播解析服务，供 media_server 的 YouTube 实时搜索与播放解析调用（`/api/providers/youtube/search|resolve`，需宿主机放置 cookies）。

App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。代理 / 部署基础设施（nginx、xray、admin 面板等）在 `workwork` 仓库，并通过 git submodule 引用本仓库来构建 `media_server`。

## 视频类型

项目把视频分成两类：

- `vod`：普通视频 / 点播视频。当前只支持这一类。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

服务端默认一级专区以 `media_server/src/contentCategories.json` 为准（当前为电影、电视剧、家庭剧场、红色经典、Dota 专区、YouTube 精选；运行时由服务端下发）。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 对局录像为主，兼顾 Dota2 和 TI 赛事。

公开 API 不暴露具体视频来源；catalog 类目只从**已审核（`approved`）**数据提供分类与视频列表。所有进 catalog 的视频先经 admin 的人工 + AI 审核才会对客户端可见。youtube 实时搜索（`/api/providers/youtube/search`）是审核关卡的受控例外：只为 App 即时展示/播放，结果不写 catalog、不进分类列表。播放地址按 provider 处理：bilibili 由 App 端用 WBI 签名本地解析，youtube 由 server 端经 vod_extractor 解析后下发全部清晰度档。

## 编译 Android TV 客户端

项目已配置 Gradle Wrapper。建议使用 Android Studio 自带 JBR 和 Android Studio 管理的 SDK：

```bash
cd media_app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

如需覆盖默认服务端 URL：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=https://your-domain.example/api/media
```

## 服务端 media_server

Go 服务，详见 `media_server/README.md`。本地编译检查：

```bash
go build -C media_server -o /dev/null .
```

`workwork` 仓库通过 git submodule 引用本仓库到 `services/media_center`，docker-compose 从 `services/media_center/media_server` 构建并部署相关服务。

部署后冒烟检查（对核心只读接口做 curl + JSON 断言，输出 PASS/FAIL，任一失败退出码 1）：

```bash
scripts/smoke.sh https://yinghuoren.com/api/media   # 或 http://localhost:8787
```

## 工具链约定

JS/Web 前端项目优先使用 Bun（`bun install`、`bun run`、`bun test`）。当前 `media_app` 是 Android TV 原生客户端，构建仍使用 Gradle/Android Studio 工具链；如果后续新增 Web 管理台或 H5 前端，应默认采用 Bun。

## API 契约

API 由本仓库的 `media_server` 维护。实现或调整 App 前先看：

- `media_server/API.md`

App 端使用 `VideoSummary.id`（nativeId，如 BV1xxx）直接调用本地解析器播放。(当前仅 bilibili 源,App 端本地解析)
