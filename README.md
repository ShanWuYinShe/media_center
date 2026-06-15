# Media Center

这个仓库同时包含媒体中心的客户端与服务端：

- `media_app`：Android TV + Kotlin + Jetpack Compose + Media3 客户端，针对遥控器焦点、电视安全边距和 2 米观看距离优化。
- `media_server`：Go 实现的合规公开视频聚合后端，提供分类、搜索、详情、播放地址与 asset 代理接口。
- `vod_extractor`：Python + yt-dlp 内部点播解析服务，只在部署方 Docker 内网中供 `media_server` 调用。

App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。代理 / 部署基础设施（nginx、xray、admin 面板等）在 `workwork` 仓库，并通过 git submodule 引用本仓库来构建 `media_server`。

## 视频类型

项目把视频分成两类：

- `vod`：普通视频 / 点播视频。当前只支持这一类。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

服务端默认一级专区为电影、电视剧、纪录片、红色经典和 Dota 专区。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 对局录像为主，兼顾 Dota2 和 TI 赛事。

公开 API 不暴露具体视频来源；后端从**已审核（`approved`）**的 catalog 提供分类与搜索结果（搜索只命中审核过的数据），并解析点播播放地址。所有视频先经 admin 的人工 + AI 审核才会对客户端可见，服务端不做实时上游搜索/采集。播放地址由服务端返回，播放器可以边下边播和 seek，不需要等整个文件下载完成。

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

`workwork` 仓库通过 git submodule 引用本仓库到 `services/media_center`，docker-compose 从 `services/media_center/media_server` 和 `services/media_center/vod_extractor` 构建并部署相关服务。

## 工具链约定

JS/Web 前端项目优先使用 Bun（`bun install`、`bun run`、`bun test`）。当前 `media_app` 是 Android TV 原生客户端，构建仍使用 Gradle/Android Studio 工具链；如果后续新增 Web 管理台或 H5 前端，应默认采用 Bun。

## API 契约

API 由本仓库的 `media_server` 维护。实现或调整 App 前先看：

- `media_server/API.md`

App 只原样使用 API 返回的图片/播放 URL，不自行构造代理地址或处理代理签名。扩展其他 Provider 时应使用官方 API 或已授权数据源，不接入盗版站点、DRM 绕过或盗链资源。
