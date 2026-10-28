---
name: media-app-release
description: media_app 版本号与 APK 发版部署流程。触发：发版、打 tag、构建 release APK、部署到服务器、写 app-update manifest 时加载。
---

# media_app 发版与部署

## 版本号与发版（git tag 驱动）

打 tag 通用流程（定 SemVer → 合并 → 主工作树打 tag 推送 → 构建）见全局 `git-workflow`「版本号以 tag 为源」；本 skill 只记 Android 特有部分。

版本号派生（`media_app/app/build.gradle.kts` 构建期自动读取，禁止手改）：`versionName` / `versionCode` 由最近 `v*` tag 自动派生，绝不手动改 versionName/versionCode。

- `versionName`：取 tag（`v1.1.0` → `1.1.0`）；tag 后有新 commit 则带 `-dev.N` 后缀（如 `1.1.0-dev.3`）。
- `versionCode`：从 SemVer 派生 `major*100000 + minor*1000 + patch`（见 `semverToCode`，忽略 dev 后缀），整数比较可靠。容量：patch 0..999、minor 0..99。
- 无 tag 时 fallback 到 `0.1.0-dev`（不阻断构建）。
- 发版构建命令：在 `media_app/` 下执行 `./gradlew :app:assembleRelease --no-daemon`（在 tag 上构建即得干净版本号）。

注意：tag 必须打在 media_center 本仓库（`ShanWuYinShe/media_center`），不要打到 workwork 聚合仓或其它仓库；版本号只由本仓库的 `v*` tag 派生。

## 部署 APK

APK 有 **normal + legacy 两个变体**：normal 源码即本目录；legacy 为 Android 4.4.2
兼容变体，源码在 `media_app_legacy/`。

- **上传位置**：`media_app/` 有变更时，编译 APK 上传到服务器工作用户 HOME 下
  `media_center/deploy/data/generated/`（compose 挂载为 media_server 的 /config，manifest 与 APK
  每次请求现读，文件就位即生效）。
- **文件名**：版本化格式 `media-center-app-v<versionCode>-<sha12>.apk`（固定名
  `media-center-app.apk` 不是上传名）。
- **manifest**：随 APK 生成 `app-update.json` 与 `app-update-legacy.json` 两份
  （versionCode / versionName / SHA256 / apkFilename）。
- **一键脚本**：`scripts/release-apk.sh` 完成构建 + 命名 + manifest 生成（+ 可选 scp 上传）。
  **该脚本即权威实现**，其头部注释与用法说明是上传命令、manifest 字段与完整流程的唯一维护处，本文件不重复。
