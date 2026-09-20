# media_app（Android TV 客户端）

`media_center` 仓库的 Android TV 客户端：Kotlin + Jetpack Compose + Media3。仓库级结构与跨端契约见上级 [AGENTS.md](../AGENTS.md)。

通用约束（Git 规范、Commit 格式、验证流程、JDK 配置等）以全局 `~/AGENTS.md` 为准，本文件只做项目特有补充。

## 构建

```bash
cd media_app
# JAVA_HOME 见全局「本机环境」Android Studio JBR 条目（以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加：Gradle/Kotlin Daemon 默认构建后 idle 保留数小时不退出（各 -Xmx2g），
# AI agent 多次构建会累积多个 java 进程撑爆内存。--no-daemon 让构建完 java 进程立即退出。
# release（非 debug）：已配 debug 签名（signingConfig=debug）+ R8 minify，体积减半且可直接安装。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

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

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 服务端地址：默认 `https://yinghuoren.com/api/media`；如需覆盖，设置 `MEDIA_SERVER_URL`。

## UI/UX 设计约定

- 固定交互控件尺寸：按钮、chip、圆形 icon button 等可聚焦/可点击控件使用 `requiredHeight` / `requiredSize` 保证可操作面积，不用普通 `height` / `size` 让父布局压缩。
- 长文本不可挤压操作区：标题、简介、错误文案、分类名、按钮文案必须设置 `maxLines` 和 `TextOverflow.Ellipsis`。详情页这类"文案 + 主操作"布局中，主操作区优先保留空间；长简介先折叠，超出后用"查看详情"弹层展示完整内容，不能在原布局内无限展开。
- 详情展开弹层：详情页简介超出折叠行数时使用紧凑"查看完整简介"披露按钮；完整简介在独立 Dialog 阅读卡片中展示，保留 BACK 关闭路径和主按钮焦点，避免在详情页原布局内撑开。
- 图标风格统一：播放、暂停、搜索、清除、箭头等图标使用 Canvas/vector/组件绘制，不把 `▶`、`⏸`、emoji 等 Unicode 字符当图标拼进 `Text`，避免 Android 字体 fallback 导致风格不一致。
- TV 焦点态一致：焦点态统一使用 Apple Blue 背景、白色文字/图标、`GlassBorderFocused` 近白外框；selected 只表达当前分类/选项，不应强过焦点态。焦点切换应使用明确的 `FocusRequester` / `focusProperties` 路径，避免依赖空间猜测。
- Back 行为稳定：详情页、播放页、搜索面板等屏幕级返回使用 `BackHandler`，不要依赖子组件焦点或 `onPreviewKeyEvent` 捕获 Back。
- Apple TV 视觉基线：OLED 深色背景、玻璃灰卡片、Apple Blue 作为唯一强调色；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
