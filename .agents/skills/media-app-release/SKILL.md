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

APK 有 **normal + legacy 两个变体**：normal 源码即本目录；legacy 为低版本兼容变体，源码在 `media_app_legacy/`（API 级别以该模块 `app/build.gradle.kts` 的 `minSdk` 为准）。

- **上传位置**：`media_app/` 有变更时，编译 APK 上传到服务器工作用户 HOME 下
  `media_center/deploy/data/generated/`（compose 挂载为 media_server 的 /config，manifest 与 APK
  每次请求现读，文件就位即生效）。
- **文件名**：版本化格式 `media-center-app-v<versionCode>-<sha12>.apk`（固定名
  `media-center-app.apk` 不是上传名）。
- **manifest**：随 APK 生成 `app-update.json` 与 `app-update-legacy.json` 两份
  （versionCode / versionName / SHA256 / apkFilename）。
- **自动化（推荐）**：仓库变量 `ENABLE_AUTO_DEPLOY=true` 且配置 deploy secrets 时，发版 workflow 自动把 APK 与两份 manifest 写入该目录（normal 与 legacy 各自保留最近 5 个版本化 APK；manifest 热加载，无需重启）；手动上传仅作兜底。
- **一键脚本**：`scripts/release-apk.sh` 完成构建 + 命名 + manifest 生成（+ 可选 scp 上传）；GitHub Actions workflow 以 `--out` 调用同一脚本。脚本头部注释是 manifest 字段与完整流程的唯一维护处，本文件不重复。

## 发布后端上走查（TV 真机优先，无 TV 真机才用模拟器）

发版（tag → CI → 自动部署）完成后、通知用户升级前，在 **TV 设备**上装**正式签名 APK**走一遍核心链路；有合格 TV 真机必须用真机，没有才退模拟器。v1.19.0/v1.19.1 两次走查各发现一个真 bug（空查询卡骨架、热词擦边），证明这一步不可省。

### 设备选择（TV 真机优先）

设备选择总则（四项合格条件、选中后固定 `-s <serial>`、记录设备信息、无合格设备时用模拟器并记录原因）见用户级 skill `android-emulator-debug`；本节只给 TV 客户端的判据与命令。

先枚举，只有状态为 `device` 的才可用（`unauthorized`/`offline` 不算）：

```bash
adb devices -l
```

再逐个核验形态与版本，都满足才算「合格 TV 真机」：

```bash
SERIAL=<serial>          # adb devices -l 第一列；TV 常走网络调试，形态为 <ip>:<port>
adb -s "$SERIAL" shell pm list features | grep -iE 'television|leanback'   # 形态主判据：TV 有 leanback/television 特性，手机无输出
adb -s "$SERIAL" shell getprop ro.build.characteristics           # 辅助：TV 含 tv（手机为 default）
adb -s "$SERIAL" shell getprop ro.build.version.sdk               # 需 ≥ 变体 minSdk：normal / legacy 各自现查
```

minSdk 以 `media_app/app/build.gradle.kts`、`media_app_legacy/app/build.gradle.kts` 现查为准。形态主判据是 `pm list features` 的 television/leanback 特性，`ro.build.characteristics` 作辅助核对；两者都不命中的设备不能替代 TV 走查。

- **优先真机**：有合格 TV 真机就必须用真机，不要再起模拟器。
- **普通手机真机不能替代 TV 形态项**：焦点/D-pad 导航、遥控器交互、Leanback 布局与遥控可操作性必须在 TV 设备上验证（`adb shell input keyevent` 只能发按键事件，验证不了遥控体验与 TV 形态）；手机真机只能覆盖与形态无关的项（APK 安装/启动、网络与解析链路、OTA 安装器流程），能否替代按当次走查项逐条判断，拿不准按「需 TV」处理。
- **记录**：走查记录与 PR 证据写明设备型号 + 系统版本 + serial（模拟器写 AVD 名，取 `"$ANDROID_HOME/emulator/emulator" -list-avds`）。
- **真机数据安全**：OTA 自更新、卸载重装、换签名都会清应用数据，动用户日常机前先确认；只做只读核验时不安装/不卸载。

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

### 安装（真机直接装；无 TV 真机才起模拟器）

选中设备后所有命令固定 `-s "$SERIAL"`：

```bash
SERIAL=<serial>          # TV 真机：adb devices -l 现查；模拟器：emulator-<端口>
adb -s "$SERIAL" install -r -t /tmp/app.apk
adb -s "$SERIAL" shell dumpsys package com.example.mediacenter | grep versionName
adb -s "$SERIAL" shell am start -W -n com.example.mediacenter/.ComposeActivity   # legacy 用 .MainActivity
```

无合格 TV 真机时才起模拟器（fallback；AVD 名用 `"$ANDROID_HOME/emulator/emulator" -list-avds` 现查，不要写死）：

```bash
"$ANDROID_HOME/emulator/emulator" -avd <AVD> -no-window -no-audio -no-boot-anim -no-snapshot-save &
# 只认 emulator-*：无 TV 真机时手机仍可能连着，不能按「第一个 device」取
SERIAL=
until [ -n "$SERIAL" ]; do
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/{print $1; exit}')
  [ -n "$SERIAL" ] || sleep 1
done
adb -s "$SERIAL" wait-for-device   # 只表示 adbd 就绪，不等于系统可用
adb -s "$SERIAL" shell getprop sys.boot_completed   # 轮询到 1
```

模拟器只作为 fallback：其渲染、性能、输入设备与真机不同，**模拟器专属限制不得当作真机结论，真机结论也不得套用模拟器坐标/性能**；走查记录写明「无 TV 真机的原因 + AVD 名」。

### 走查检查单（发现过真 bug 的项标 ★）

- ★ 搜索页**空输入直接按确认键**：必须留在空态。回归过一次卡死骨架的 bug（pending 标记 + 空提交组合）。
- ★ 搜索热词点选出结果：确认热词内容与当前搜索语义匹配（B 站已改全站搜索，热词须家庭友好，见 media_app AGENTS.md）。
- ★ 首页分类标签按 **UP**：焦点应直接落顶栏「设置」（normal 与 legacy 都修过 UP 被吞的问题）。
- ★ **视频播放抽查必须随机、不固定测试集**：每次走查看 B 站与 YouTube 两个源、跨多个分类随机点 6–10 个视频（分类/卡片位置随机，覆盖电影、电视剧、家庭剧场、红色经典、Dota 专区与 YouTube 音乐/科技/游戏/纪录片/电影/学习），逐个播放到出画面。固定点某几个高频视频会漏掉"个别视频加载失败"（v1.19.3 随机抽查 456 部电影，发现 39 部源站已删/不可见——这些在 App 里都表现为加载失败）。
- YouTube 源封面全部显示（走服务端缩略图代理）；播放走播放流中转，冷解析 25–30s 属正常。
- B 站源播放（本地 WBI 解析链路）。
- 离开播放页返回首页：焦点恢复到离开前的卡片。

### OTA 自更新验证（发版后按需）

更新通道（`/api/v1/app/update`）和安装器链路要在设备上过一遍（OTA 属形态无关项，手机真机也可覆盖；无合格真机才用模拟器；真机上换版本前先确认，卸载重装会清应用数据），只看 manifest 不算验证：

1. 装**上一版** release APK（GitHub Release 资产，或 `gh run download <run_id> -n release-apk-<run_id>`），启动后首页应出现「发现新版本」弹窗。
2. 点「更新」：正常路径是系统安装确认框（PackageInstaller），确认后版本更新、应用数据保留。
3. 若 PackageInstaller session 被系统拒绝（部分镜像/系统策略返回 `STATUS_FAILURE`），App 自动回退 `ACTION_INSTALL_PACKAGE` 系统安装器；连回退都发不出去时弹「更新失败 + 原因 + 重试」。排查看日志 `MediaCenter.Update: install result: status=… message=…`。
4. 需要在本机制造「已装版本 < 线上版本」以触发更新流程时：临时把对应变体的 `build.gradle.kts`（normal `media_app/app/`、legacy `media_app_legacy/app/`）的 versionCode/versionName 改成旧值构建 debug APK（**构建完立即还原，禁止提交**），验证后再用正式 APK 覆盖安装。两个变体共用同一 applicationId，切换变体验证前先 uninstall。
5. 走查**更新流程 UI**（发现新版本/下载进度/后台下载）时不必真发新版本，也不要改 build.gradle，用本机 mock 更新服务即可：
   - 宿主机起 HTTPS mock（`https://10.0.2.2:8443`，自签证书带 `IP:10.0.2.2` SAN；legacy 的 SoftFailTrustManager 接受、normal 不接受自签）；
   - `/api/v1/*` 代理真实 `media.chaoset.com`（目录接口预热缓存，否则首页会卡「正在加载」十几秒，和更新流程混在一起无法判断）；只覆写 `/api/v1/app/update` 返回更大 versionCode 的清单，以及更新包下载地址按固定码率节流（如 320KB/s）——不节流时本地下载瞬完，截不到百分比中间态；
   - APK 用 `-PMEDIA_SERVER_URL=https://10.0.2.2:8443/api/v1` 构建；改前/改后 APK 必须共用同一 mock、同一码率、同一更新包，才是同条件对比；
   - 进度截图在同一条脚本里「按更新 → 固定间隔 `screencap`」，人工操作间隔会跨过整个下载窗口；走查结束杀掉 mock 进程。
6. 跨版本原位升级前先核对两个 APK 证书一致：`apksigner verify --print-certs a.apk b.apk` 的 certificate SHA-256 必须相同；不同则只能卸载重装（应用数据会丢）。

### 抽查发现"视频加载失败"时的处置

判定看 logcat 的 `MediaCenter.Perf` / `MediaCenter.Player`：

- `resolvePlayback failed: view 失败(-404/62002/62012)` 或 playurl 同类错误 = **catalog 脏数据**（源站视频已删/不可见），按下面的巡检流程清理；不要当成网络问题重试。
- `resolvePlayback failed` 为风控/超时类（-352/-412/HTTP 4xx/5xx）或 YouTube resolve 超时 = 解析链路问题，重测一次区分偶发；持续则查服务器 `/healthz` 的 `youtube` 熔断与 `vod_extractor`。
- 有 `resolve` 无 `player first frame` = 直链/中转/解码问题，看 media warmup 与 player error 日志。

catalog 巡检（开发机跑，B 站接口受服务器 IP 风控限制）：

```bash
python3 scripts/check-bilibili-catalog.py --output /tmp/catalog-check.json   # 全量 dry-run，输出失效清单
```

确认清单后按 media-server-catalog skill「可播放性巡检」下架（软 reject，可恢复）。

### 判定与收尾

- 「真的跑起来了」要**截图 + 前台 Activity + 进程 PID 三者交叉**（见 android-emulator-debug skill）。
- 结果判定优先看 `Perf:` 日志链（resolve → controller connected → prepared → first frame），不是只看 UI。
- 收尾：用过模拟器必须 `adb -s <serial> emu kill` 并在 `adb devices` 里确认该实例消失；真机不执行 `emu kill`，测试 APK 按需卸载（会清应用数据，先确认）；删除 /tmp 下的 APK 与截图。
