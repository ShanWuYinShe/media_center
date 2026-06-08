# Android TV App

这个目录只包含 Android TV 客户端。服务端实现已经迁移到 `workwork/services/media_server`。App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。

使用 Android Studio 自带工具链编译：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

如需覆盖默认服务端 URL：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=https://your-domain.example/api/media
```

`MEDIA_SERVER_URL` 是 App 唯一需要的服务端配置；可以是服务根地址，也可以直接带 API 路径。API 契约由 `workwork/services/media_server/API.md` 和 `workwork/services/media_server/openapi.json` 维护。

首页会从服务端读取内容分类，默认展示服务端返回的第一个分类。内置一级专区包括电影、电视剧、纪录片、红色经典和 Dota 专区。App 只消费服务端返回的统一视频 ID、`videoKind=vod` 和点播播放地址，不处理直播、视频来源、网页播放或外部打开。
