---
name: media-app-release
description: media_app 版本号与 APK 发版部署流程。触发：发版、打 tag、构建 release APK、部署到服务器、写 app-update manifest 时加载。
---

# media_app 发版与部署

## 版本号与发版（git tag 驱动）

打 tag 通用流程（定 SemVer → 合并 → 主工作树打 tag 推送 → 构建）见全局 `git-workflow`「版本号以 tag 为源」；本 skill 只记 Android 特有部分。

版本号派生（`build.gradle.kts` 构建期自动读取，禁止手改）：`versionName` / `versionCode` 由最近 `v*` tag 自动派生，绝不手动改 versionName/versionCode。

- `versionName`：取 tag（`v1.1.0` → `1.1.0`）；tag 后有新 commit 则带 `-dev.N` 后缀（如 `1.1.0-dev.3`）。
- `versionCode`：从 SemVer 派生 `major*100000 + minor*1000 + patch`（见 `semverToCode`，忽略 dev 后缀），整数比较可靠。容量：patch 0..999、minor 0..99。
- 无 tag 时 fallback 到 `0.1.0-dev`（不阻断构建）。
- 发版构建命令：`./gradlew :app:assembleRelease --no-daemon`（在 tag 上构建即得干净版本号）。

注意：tag 要打在 media_center 仓库（非外层 `workwork` 仓库）；版本号与外层仓库的提交/tag 无关。

## 部署 APK

APK 有 **normal + legacy 两个变体**：normal 源码即本目录；legacy 为 Android 4.4.2
兼容变体，源码在 `media_app_legacy/`。部署时如 `media_app/` 有变更，编译 APK 上传到
服务器 `.wework/generated/media_center/`，文件名为版本化格式
`media-center-app-v<versionCode>-<sha12>.apk`（admin 发布管理按此命名索引，固定名
`media-center-app.apk` 不是上传名），随后手写 `app-update.json` 与
`app-update-legacy.json` 两份 manifest（versionCode / versionName / SHA256 /
apkFilename）。上述构建 + 命名 + manifest 生成（+ 可选 scp 上传）可用
`scripts/release-apk.sh` 一键完成；上传命令、manifest 字段与完整流程仍以
`workwork` 仓库 README「media_app」节为唯一维护处，此处不重复。

