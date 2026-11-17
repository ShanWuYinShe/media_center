# media_app（Android TV 客户端）

`media_center` 仓库的 Android TV 客户端：Kotlin + Jetpack Compose + Media3。仓库级结构与跨端契约见上级 [AGENTS.md](../AGENTS.md)。

通用约束（Git 规范、Commit 格式、验证流程、JDK 配置等）以全局 `~/AGENTS.md` 为准，本文件只做项目特有补充。

## 构建

```bash
cd media_app
# JAVA_HOME 见 skill local-environment（Android Studio JBR 路径；以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加（原因见 skill toolchain-setup：Daemon 构建后 idle 数小时不退出，各占 -Xmx2g）
# release（非 debug）：R8 minify + 资源压缩，体积减半且可直接安装。
# 正式发版由 GitHub Actions 在 tag 上构建并注入固定发布签名（见根 AGENTS.md）；
# 本地构建未注入 ANDROID_SIGNING_* 时回退 debug 签名，仅用于调试。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

## Baseline Profile（启动 AOT）

`app/src/main/baseline-prof.txt` 是 App 自有启动路径的 baseline profile 规则（显式类/方法规则，AGP 构建期用 R8 mapping 映射到混淆名后合并进 APK 的 `assets/dexopt/baseline.prof`；依赖库自带 profile 由 AGP 一并合并）。已内置 `androidx.profileinstaller`（经依赖传递），侧载 TV 安装后由它在启动时把 profile 安装给 ART，后续冷启动走 AOT 代码。

- **生成/更新**：从 CI 下载 `r8-mapping-<run_id>` 产物（`gh run download <run_id> -n r8-mapping-<run_id>`），执行 `python3 scripts/gen-baseline-profile.py <mapping.txt> media_app/app/src/main/baseline-prof.txt`。规则选择启动路径的包前缀，统一 `HSPL`，并加关键类预分配规则。
- **通配符不生效**：AGP 9.2.1 的 app 模块实测不展开 HRF 通配符，必须显式展开（生成器负责）。
- **验证**：`unzip -l <apk> | grep baseline.prof` 体积应随之变化；模拟器/真机执行 `adb shell cmd package dump-profiles --dump-classes-and-methods com.example.mediacenter` 后确认安装后的 profile 含新规则。

## 版本号与发版、部署 APK

tag 驱动版本号、双变体 APK 构建与 manifest 部署流程见 skill media-app-release。

正式发版统一由 GitHub Actions 完成：push `vX.Y.Z` tag → `.github/workflows/release-app.yml` 构建 normal + legacy release APK、发布 GitHub Release、可选自动部署到服务器；本地 `assembleRelease` 只用于调试。发版 secrets、签名与自动部署配置见仓库根 AGENTS.md「APK 发版（GitHub Actions）」。

## TV UI/UX 设计约定（详见 skill media-app-tv-ui）

TV 交互与视觉约定见 skill media-app-tv-ui。

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 播放补充：YouTube 1080P+ 的分离音轨用 `MergingMediaSource` 合流；B 站多段 durl 用 `ConcatenatingMediaSource` 顺序拼接（MediaController 只能传 MediaItem，额外地址经 MediaMetadata extras 传给 PlaybackService 的 `PlaybackMediaSourceFactory`）。
- 默认清晰度：自动选档按「屏幕物理高度与设备档位取小」收敛（`data/PlaybackTuning`，低端机 ≤720P），手动切清晰度不受限；离开播放页后播放服务保活 20s（`player/PlaybackServiceKeepAlive`），快速回看不重建服务/播放器。
- 服务端地址：默认 `https://media.chaoset.com/api/v1`；如需覆盖，设置 `MEDIA_SERVER_URL`。

## 代码结构约定

- ViewModel 依赖 `MediaRepositoryApi` 接口（`data/MediaRepositoryApi.kt`），单测用 fake 注入，不依赖 OkHttp/SharedPreferences。
- HTTP 调用统一 `data/HttpCall.kt` 的 `Call.await()`；响应体读取必须在 IO 调度器。
- 通用 TV 控件集中在 `ui/components/`（`TvActionButton`、`TvChip`、`TvDialogButton`、`PagedVideoGrid`、`VideoCard` 等），页面不要重复按钮样式或分页网格。
- 播放器：`PlayerViewModel` 负责解析/清晰度/分P/合集 next 决策；`PlayerControllerConnection` 负责 MediaController 重连与幂等释放；`PlayerAudioFocus` 负责音频焦点；`PlayerContent` 只编排播放与覆盖层。
- 起播耗时埋点：进入播放页后按链路打 `Perf: player controller connected in Nms` → `player prepared in Nms` → `player stream ready in Nms` → `player first frame in Nms (videoId, page, epoch)`；换视频/分P/清晰度/重试都重新计时，与 `Perf: resolve ...`、`prefetch ready` 串起来可定位"解析→控制器连接→prepare→缓冲就绪→首帧"各段耗时。
- 起播优化：解析成功后 `PlaybackMediaCache` 把首段媒体预取进 Media3 `SimpleCache`——按自动选档上限（`PlaybackTuning.maxInitialQualityQn`，与播放页同口径）选首档，视频第一段 + 独立音轨各 768KB，LRU 128MB（低端）/256MB，同一 URL 5 分钟只预取一次，HLS/DASH 清单与 `data:` 不预取；播放数据源套 `CacheDataSource`（`FLAG_IGNORE_CACHE_ON_ERROR`，只读缓存），命中即读磁盘、未命中/损坏自动回退网络。日志 `Perf: media prefetch <N>KB cached in Nms (read=NKB)`。
- 首页由 `HomeViewModel` 一次只加载当前分类（10 分钟缓存 + 分页；冷启动先用 `filesDir` 快照 SWR 渲染，缓存过期也先展示旧内容再后台刷新），分类切换由 `HomeCategoryTabs` 焦点触发；当前分类加载完成后会后台预热左右相邻分类的第一页（youtube 实时通道不预热），左右键切类直接命中缓存；「继续观看」读本地 `WatchHistory`。UI 不做磁盘 IO。
- 焦点恢复：首页用 `rememberSaveable` 记录最后聚焦的卡片，经 `PagedVideoGrid(initialFocusItemId/onItemFocused)` 在从详情/播放/搜索返回时恢复到该卡片（无记录则回当前分类标签，焦点不会丢给顶栏）；搜索页同样恢复结果卡，且返回时不再重新弹出输入法。
