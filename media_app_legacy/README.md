# Android TV App

> **API 19 兼容变体**：面向 Android 4.4 老盒子。视觉 tokens（深色背景 / 玻璃卡片 / Apple Blue / 焦点外框）、TopBar+Hero、视频源切换与 YouTube 分类已与新版对齐；架构上使用 `MediaRepositoryApi` 契约与纯函数展示模型。为兼容旧设备仍用原生 View + RecyclerView，不引入 Compose / Coil 2.x / TV Material。

这个目录只包含 Android TV 客户端。服务端实现为[本仓库](../media_server/)的 `media_server`（部署编排在本仓库 `deploy/` 内，非 workwork 自有代码；早前 workwork 以 submodule 引用构建的方式已废弃）。App 只需要一个服务端地址 `MEDIA_SERVER_URL`，未设置时回落到内置默认 `https://media.chaoset.com/api/v1`。

## Android Studio 菜单工作流

用 Android Studio 直接打开 `media_app_legacy/` 目录，等待 Gradle Sync 完成后即可通过菜单操作，不需要手动调用 `gradlew`。

1. 编译：使用 **Build > Make Project**。
2. 运行：选择工具栏的 `app` 运行配置，然后使用 **Run > Run 'app'**。
3. 打包调试 APK：使用 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. 打包发布 APK：使用 **Build > Generate Signed Bundle / APK...**。

App 仍按 Android 4.4.2/API 19 设计，使用原生 View UI（不打包 Compose、Coil 2.x、TV Material 等 API 21+ 依赖），但视觉与交互口径跟随新版；老旧低内存盒子优先保证启动速度、焦点稳定与图片缓存上限。TLS（Conscrypt/信任库）与播放媒体 SimpleCache 在后台线程预热，不占冷启动首帧；首个网络客户端/首次播放会同步等待就绪（≤10s 兜底），避免老盒子用平台 TLS 握手失败。

## B 站多 P 视频支持

B 站单视频（bvid）可能包含多个分 P（如 P1/P2/P3）。App 自动识别多 P：

- **列表预取**：仅目录型 provider（B 站，本地解析）在浏览分类时后台解析**最多 2 条**播放地址并缓存页数信息，且只对第一条预热首段媒体缓存（避免每屏数 MB 流量与首屏抢带宽）；YouTube 实时通道解析是服务端 yt-dlp 重操作，浏览时不预取，避免占满服务端解析槽位
- **详情页**：进入详情后自动解析首页播放地址
- **播放页按"下"**：多 P 视频先依次翻页（P1→P2→P3），播完所有 P 后跳转列表下一个视频
- **单 P 视频**：按"下"直接跳到列表下一个视频

播放页的「正在重试…」遮罩只在**播放失败自动恢复**（短期直链失效/瞬时 5xx 时按 1.5s/3s/4.5s 退避重新解析）期间显示；切清晰度显示独立的「正在切换清晰度…」提示并保留播放进度（换流 prepare 时 seek 回重建前位置），切分 P/跳下一个视频只置 loading，避免用户误以为播放失败在重试。详情页背景图使用卡片尺寸缩略图放大模糊后铺底，前景大图只在信息区渲染，避免同一张大图下载/解码两份。

下载器不落盘；每次播放前调用 `BilibiliApi.resolvePlayback` 获取最新直链（播放媒体的首段磁盘缓存见下文，不影响"直链时效内播放"的语义）。

如需覆盖默认服务端 URL：

在 Android Studio 里可以把 `MEDIA_SERVER_URL=https://your-domain.example/api/v1` 写入 `media_app_legacy/gradle.properties` 或用户级 `~/.gradle/gradle.properties`，然后重新 Sync。

`MEDIA_SERVER_URL` 是 App 唯一需要的服务端配置；可以是服务根地址，也可以直接带 API 路径。API 契约由 `../media_server/API.md` 维护。

**国内访问加速（Cloudflare 优选 IP）**：服务端在 Cloudflare 后面，国内运营商 DNS 常把 media 域名解析到绕路/被 QoS 的 anycast 节点（线上实测：走系统 DNS 15 次请求 8 次连不上、成功时首字节 3.7s；绑定优选 IP 后 15/15 成功、首字节 1.0s）。`net/PreferredIpStore.kt` 从 `/api/v1/preferred-ips` 拉候选池、后台做 TLS 握手测速并按延迟排序、结果落 SharedPreferences，经 OkHttp `Dns` **只接管 `media.chaoset.com`**，B 站图床/上游 API 等其它域名仍走系统 DNS。降级链：服务端池 → 内置兜底池 → 系统 DNS，任何一步失败都不影响可用性。

首页会从服务端读取内容分类，默认展示服务端返回的第一个分类；当前分类加载完成后会后台预热左右相邻分类第一页（仅目录型 provider，YouTube 实时分类不预热），遥控器切 chip 直接命中 10 分钟分类缓存。一级专区以服务端下发为准（见 `../media_server/src/contentCategories.json`）。App 只消费服务端返回的统一 opaque 视频 ID、opaque 播放选项 ID、`videoKind=vod` 和点播播放地址，并原样播放服务端返回的图片/播放 URL；不处理直播、视频来源、网页播放、外部打开或代理 URL 签名。

弱网/服务端不可达时，首页最多等待约 20s 就落错误态并停止自动重试（此前最多 3 轮 × 单请求重试，可能让用户盯着“自动重试中”好几分钟）；网络恢复后由网络回调自动重试。

播放前调用 BilibiliApi.resolvePlayback 获取最新播放地址，播放器使用 ExoPlayer `DefaultHttpDataSource` 直接流式读取服务端返回的 URL（经 `player/LegacyPlaybackMediaCache` 的 `CacheDataSource` 包装）。客户端不做下载器；但会做**播放媒体磁盘缓存**：解析成功后预取首段头部 768KB + 尾部 2MB（moov 兜底），播放 write-through 边播边写，重看/续播直接命中本地缓存秒开（LRU 128MB，cacheDir 内，异常自动回退网络，日志 `Perf: media prefetch ...` / `playback cache read`）。

首页内容加载完成后（最迟启动 12s 兜底、且不在播放/详情/搜索页时）App 才会请求 `/api/app/update?versionCode=当前版本&variant=legacy` 检查更新，避免启动瞬间下载 APK 与首屏/缩略图抢带宽；发现新版本时自动下载 APK、校验 SHA-256，然后拉起系统安装器。若下载完成或检查失败时用户正在播放/看详情/搜索，提示会推迟到回到首页再弹出（避免盖住画面、抢走遥控器焦点导致误触）。普通 Android TV 应用不能绕过系统权限静默安装，首次需要用户允许“安装未知应用”并确认安装。
