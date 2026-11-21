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
- **正式发版构建由 GitHub Actions 完成**：push `vX.Y.Z` tag 触发 `.github/workflows/release-app.yml`（构建 normal + legacy、生成两份 manifest、发布 GitHub Release、可选自动部署）；本地 `assembleRelease` 只用于调试。发版 secrets、固定签名与自动部署配置以仓库根 `AGENTS.md`「APK 发版（GitHub Actions）」为唯一维护处。

注意：tag 必须打在 media_center 本仓库（`ShanWuYinShe/media_center`），不要打到 workwork 聚合仓或其它仓库；版本号只由本仓库的 `v*` tag 派生。

## 构建期令牌（YouTube 源可用性硬前提）

- `media_app/app/build.gradle.kts` 从 **Gradle property / 环境变量 `APP_API_TOKEN`** 注入 `BuildConfig.APP_API_TOKEN`；它与服务端 `MEDIA_SERVER_APP_API_TOKEN` 必须一致。
- 该令牌是 YouTube 实时搜索/解析与凭证接口的 `X-App-Token`。**未注入时 APK 仍能构建，但这两个通道一律 401**：App 里 YouTube 源搜不到、播不了，B 站也只能走无凭证清晰度。
- 本地构建可在 shell 中注入（只读使用，禁止写入代码、脚本、文档或日志）；正式发版由 GitHub Actions 从仓库 secret `APP_API_TOKEN` 读取，缺失时 tag 发版直接失败（避免产出 YouTube 401 的包）。构建产物的版本号由 tag 派生，令牌不体现在版本号里。
- 排障：若用户反馈“切到 YouTube 后搜索失败/凭证 401”，先核对 APK 构建环境是否注入了该变量，再看服务端 `MEDIA_SERVER_APP_API_TOKEN`。

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
- **自动化（推荐）**：仓库变量 `ENABLE_AUTO_DEPLOY=true` 且配置 deploy secrets 时，发版 workflow 自动把 APK 与两份 manifest 写入该目录（normal 与 legacy 各自保留最近 5 个版本化 APK；manifest 热加载，无需重启）；手动上传仅作兜底。
- **一键脚本**：`scripts/release-apk.sh` 完成构建 + 命名 + manifest 生成（+ 可选 scp 上传）；GitHub Actions workflow 以 `--out` 调用同一脚本。脚本头部注释是 manifest 字段与完整流程的唯一维护处，本文件不重复。

## 发布后模拟器走查（发版门禁的最后一步）

发版（tag → CI → 自动部署）完成后、通知用户升级前，在模拟器装**正式签名 APK**走一遍核心链路。v1.19.0/v1.19.1 两次走查各发现一个真 bug（空查询卡骨架、热词擦边），证明这一步不可省。

### 取 APK 与核对（防装错变体）

```bash
# 从服务器更新通道取（normal 的文件名以 app-update.json 的 apkFilename 为准；scp 通配符
# 会同时匹配 normal+legacy 两个变体并把目标当目录,必须用精确文件名）:
scp workwork-server:media_center/deploy/data/generated/<apkFilename> /tmp/app.apk
# 或从 GitHub Release 取: gh release download vX.Y.Z --pattern 'media-center-app-normal-*'
BT=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
"$ANDROID_HOME/build-tools/$BT/aapt2" dump badging /tmp/app.apk | grep -E "^package:|launchable-activity"
```

变体判别：normal 的 launchable-activity 是 `ComposeActivity`，legacy 是 `MainActivity`——装错变体会"安装成功但走查的代码对不上"。

### 模拟器与安装

```bash
"$ANDROID_HOME/emulator/emulator" -avd MediaCenterTV -no-window -no-audio -no-boot-anim -no-snapshot-save &
adb wait-for-device   # 不等于系统可用
adb -s emulator-5554 shell getprop sys.boot_completed   # 轮询到 1
adb -s emulator-5554 install -r -t /tmp/app.apk
adb -s emulator-5554 shell dumpsys package com.example.mediacenter | grep versionName
adb -s emulator-5554 shell am start -W -n com.example.mediacenter/.ComposeActivity   # legacy 用 .MainActivity
```

### 走查检查单（发现过真 bug 的项标 ★）

- ★ 搜索页**空输入直接按确认键**：必须留在空态。回归过一次卡死骨架的 bug（pending 标记 + 空提交组合）。
- ★ 搜索热词点选出结果：确认热词内容与当前搜索语义匹配（B 站已改全站搜索，热词须家庭友好，见 media_app AGENTS.md）。
- ★ 首页分类标签按 **UP**：焦点应直接落顶栏「设置」（normal 与 legacy 都修过 UP 被吞的问题）。
- YouTube 源封面全部显示（走服务端缩略图代理）；选一个视频播放到出画面（走播放流中转，冷解析 25–30s 属正常）。
- B 站源播放（本地 WBI 解析链路）。
- 离开播放页返回首页：焦点恢复到离开前的卡片。

### 判定与收尾

- 「真的跑起来了」要**截图 + 前台 Activity + 进程 PID 三者交叉**（见 android-emulator-debug skill）。
- 结果判定优先看 `Perf:` 日志链（resolve → controller connected → prepared → first frame），不是只看 UI。
- 收尾必须 `adb emu kill` 并确认 `adb devices` 里模拟器消失；删除 /tmp 下的 APK 与截图。
