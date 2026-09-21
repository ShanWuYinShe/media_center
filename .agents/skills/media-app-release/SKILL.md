---
name: media-app-release
description: media_app 版本号与 APK 发版部署流程。触发：发版、打 tag、构建 release APK、部署到服务器、写 app-update manifest 时加载。
---

# media_app 发版与部署

## 版本号与发版（git tag 驱动）

版本号规范（构建期自动读取，禁止手改 `build.gradle`）：`versionName` / `versionCode` 由 `build.gradle.kts` 在构建期用 `git describe` 读取最近 `v*` tag 自动派生，绝不手动改 versionName/versionCode。

- `versionName`：取 tag（`v1.1.0` → `1.1.0`）；tag 后有新 commit 则带 `-dev.N` 后缀（如 `1.1.0-dev.3`）标识未发布构建。
- `versionCode`：从 SemVer 派生 `major*100000 + minor*1000 + patch`（见 `semverToCode`，忽略 dev 后缀），整数比较可靠。容量：patch 0..999、minor 0..99。
- 升版规则：破坏性改动升 major、新功能升 minor、bug 修复升 patch。无 tag 时 fallback 到 `0.1.0-dev`（不阻断构建）。

AI 更新版本流程（代码改完、发版前执行）：版本号只由 git tag 决定，打完 tag 即“自动更新版本”，无需改任何文件。commit 在对应 Worktree 内完成；合并进主分支后，tag 与 push（含 tag）一律在主工作树执行（见全局 Git 准则）。

1. 在 Worktree 内提交代码变更（确保要发布的 commit 已就绪）。
2. 按变更类型决定下一 SemVer：`major`（破坏性）/`minor`（新功能）/`patch`（bug 修复）。
3. 合并进主分支后，在主工作树于当前 commit 打 tag 并推送：`git tag vX.Y.Z && git push origin vX.Y.Z && git push origin main`（tag 必须在要发布的 commit 上）。
4. 随后构建（`./gradlew :app:assembleRelease --no-daemon`）即得到干净版本号（`git describe` 在 tag 上产出 `X.Y.Z`，无 `-dev.N`）。

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

