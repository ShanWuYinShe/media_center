# media_app（Android TV 客户端）

`media_center` 仓库的 Android TV 客户端：Kotlin + Jetpack Compose + Media3。仓库级结构与跨端契约见上级 [AGENTS.md](../AGENTS.md)。

通用约束（Git 规范、Commit 格式、验证流程、JDK 配置等）以全局 `~/AGENTS.md` 为准，本文件只做项目特有补充。

## 构建

```bash
cd media_app
# JAVA_HOME 见 skill local-environment（Android Studio JBR 路径；以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加（原因见 skill toolchain-setup：Daemon 构建后 idle 数小时不退出，各占 -Xmx2g）
# release（非 debug）：已配 debug 签名（signingConfig=debug）+ R8 minify，体积减半且可直接安装。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 版本号与发版、部署 APK（详见 skill media-app-release）

tag 驱动版本号、双变体 APK 构建与 manifest 部署流程已移入 skill media-app-release。

## TV UI/UX 设计约定（详见 skill media-app-tv-ui）

TV 交互与视觉约定已移入 skill media-app-tv-ui。

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 服务端地址：默认 `https://media.chaoset.com/api/v1`；如需覆盖，设置 `MEDIA_SERVER_URL`。

