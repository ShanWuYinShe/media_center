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
- **验证**：`unzip -l <apk> | grep baseline.prof` 体积应随之变化；在真机执行 `adb -s <serial> shell cmd package dump-profiles --dump-classes-and-methods com.example.mediacenter` 后确认安装后的 profile 含新规则（无合格真机才用模拟器；设备选择判据见 skill `media-app-release`「设备选择」）。

## 版本号与发版、部署 APK

tag 驱动版本号、双变体 APK 构建与 manifest 部署流程见 skill media-app-release。

正式发版统一由 GitHub Actions 完成：push `vX.Y.Z` tag → `.github/workflows/release-app.yml` 构建 normal + legacy release APK、发布 GitHub Release、可选自动部署到服务器；本地 `assembleRelease` 只用于调试。发版 secrets、签名与自动部署配置见仓库根 AGENTS.md「APK 发版（GitHub Actions）」。

## TV UI/UX 设计约定（详见 skill media-app-tv-ui）

TV 交互与视觉约定见 skill media-app-tv-ui。

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 播放补充：YouTube 1080P+ 的分离音轨用 `MergingMediaSource` 合流；B 站多段 durl 用 `ConcatenatingMediaSource` 顺序拼接（MediaController 只能传 MediaItem，额外地址经 MediaMetadata extras 传给 PlaybackService 的 `PlaybackMediaSourceFactory`）。
- 默认清晰度：自动选档按「屏幕物理高度与设备档位取小」收敛（`data/PlaybackTuning`，低端机 ≤720P），手动切清晰度不受限；带 qn 重试/切页时同样按已选档重排（`PlaybackTuning.preferOption`），避免服务端降序结果把清晰度重置成最高档。离开播放页后播放服务保活 20s（`player/PlaybackServiceKeepAlive`），快速回看不重建服务/播放器。
- 播放页存活期间设置 `KEEP_SCREEN_ON`（播放器/MediaSession 自身不持屏），避免长视频无人操作时被电视屏保或节能策略熄屏。
- 服务端地址：默认 `https://media.chaoset.com/api/v1`；如需覆盖，设置 `MEDIA_SERVER_URL`。
- 国内访问加速（Cloudflare 优选 IP）：服务端在 CF 后面，国内运营商 DNS 常把 media 域名解析到绕路/被 QoS 的 anycast 节点（实测 15 次里 8 次连不上、成功时首字节 3.7s；绑定优选 IP 后 15/15 成功、首字节 1.0s）。`net/PreferredIpStore.kt` 从 `/api/v1/preferred-ips` 拉候选池、后台 TLS 握手测速排序、结果持久化，经 OkHttp `Dns` **只接管 `media.chaoset.com`**，其它域名仍走系统 DNS。降级链：服务端池 → 内置兜底池 → 系统 DNS，任何一步失败都不影响可用性。legacy 变体（`media_app_legacy`）有同语义实现，改动时两端一起改。

## 代码结构约定

- ViewModel 依赖 `MediaRepositoryApi` 接口（`data/MediaRepositoryApi.kt`），单测用 fake 注入，不依赖 OkHttp/SharedPreferences。
- HTTP 调用统一 `data/HttpCall.kt` 的 `Call.await()`；响应体读取必须在 IO 调度器。
- 通用 TV 控件集中在 `ui/components/`（`TvActionButton`、`TvChip`、`TvDialogButton`、`PagedVideoGrid`、`VideoCard` 等），页面不要重复按钮样式或分页网格。
- 播放器：`PlayerViewModel` 负责解析/清晰度/分P/合集 next 决策；`PlayerControllerConnection` 负责 MediaController 重连与幂等释放；`PlayerAudioFocus` 负责音频焦点；`PlayerContent` 只编排播放与覆盖层。
- 起播耗时埋点：进入播放页后按链路打 `Perf: player controller connected in Nms` → `player prepared in Nms` → `player stream ready in Nms` → `player first frame in Nms (videoId, page, epoch)`；换视频/分P/清晰度/重试都重新计时，与 `Perf: resolve ...`、`prefetch ready` 串起来可定位"解析→控制器连接→prepare→缓冲就绪→首帧"各段耗时。
- 起播优化：解析成功后 `PlaybackWarmup` 在后台用平台 `HttpURLConnection` 预热直链 CDN 连接（`Range: bytes=0-1023`，同一路径 60s 一次，失败静默）；播放页拉首段时复用已建立的连接——媒体预取走 `PlaybackMediaCache` 自己的数据源栈，连接池与 Media3 拉流不共享，两者互补。日志 `Perf: media warmup <host> in Nms (http=code)`。
- 首页由 `HomeViewModel` 一次只加载当前分类（10 分钟缓存 + 分页；冷启动先用 `filesDir` 快照 SWR 渲染，缓存过期也先展示旧内容再后台刷新），分类切换由 `HomeCategoryTabs` 焦点触发；当前分类加载完成后会后台预热左右相邻分类的第一页（youtube 实时通道不预热），左右键切类直接命中缓存；「继续观看」读本地 `WatchHistory`。UI 不做磁盘 IO。
- 焦点恢复：首页用 `rememberSaveable` 记录最后聚焦的卡片，经 `PagedVideoGrid(initialFocusItemId/onItemFocused)` 在从详情/播放/搜索返回时恢复到该卡片（无记录则回当前分类标签，焦点不会丢给顶栏）；搜索页同样恢复结果卡，且返回时不再重新弹出输入法。
- 搜索是**提交式**：输入框 `onQueryChange` 只更新文本，不发起请求；只有显式提交（IME 搜索键 / 热词 / 历史 chip / 重试，统一走 `SearchViewModel.submit`）才真正搜索。TV 上边输入边搜会把每个按键都变成一次请求，且用户没输完就看到半截结果。
- B 站搜索走**全站**实时通道（服务端 `/api/v1/providers/bilibili/search`，见 `bilibili_search.go`）：目录搜索只覆盖已审核条目，搜全站视频会大面积搜不到。结果按页返回（每页 20 条，bvid 可直接播放）。
- B 站搜索**热词面向全站语义**：用演员名/片名/曲艺类名词（周星驰/京剧/相声…），不用泛类型词（动作/爱情…实测会命中擦边内容，家庭场景不合适）。改热词时 normal（`SearchScreen.kt`）与 legacy（`theme/FocusTargets.kt`）两端一起改。
- 焦点导航：首页分类标签按 **UP** 直接进入顶栏按钮区（落到「设置」，左键视频源、右键搜索）；此前 UP 被吞掉，只能一路右移到最右侧才够到顶栏。内容区按 UP 回分类标签的链路保持不变。
