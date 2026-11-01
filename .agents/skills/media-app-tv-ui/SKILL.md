---
name: media-app-tv-ui
description: media_app TV 界面专属设计约定：Apple TV 视觉基线、焦点态配色与外框、详情页简介折叠与披露弹层。触发：新增或修改 media_app 的 TV 界面、控件样式、焦点态样式、详情页与播放页布局。
---

# media_app TV UI/UX 设计约定（项目专属）

**通用约束**（可操作面积、长文本截断、图标与无障碍、系统级返回、焦点切换与焦点态语义）**见用户级 skill `mobile-ui-conventions`，本文件不复述**；以下只记本项目特有的视觉与交互设计。

- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为**唯一强调色**；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
- **TV 焦点态**：统一用 `ui/components` 的 `TvActionButton` / `TvChip` / `TvDialogButton` / `TvIconButton`（Apple Blue + `FocusBorder` #E5E5EA 2dp 外框 + 1.04x）；海报卡用 `VideoCard` 的 focusedBorder（3dp）。`selected` 用暗蓝，只表达当前分类/选项，**不应强过焦点态**。
- **详情展开弹层**：详情页简介超出折叠行数时，用紧凑的「查看完整简介」披露按钮；完整简介在**独立 Dialog 阅读卡片**中展示，需保留 BACK 关闭路径与主按钮焦点，**不得在详情页原布局内撑开**。

## 排版与色彩

- 使用 `ui/theme/Theme.kt` 的 `TvTypography`（10ft 尺度）：headlineLarge 42sp / headlineMedium 32sp / titleLarge 22sp / titleMedium 19sp / bodyLarge 18sp / bodyMedium 16sp / bodySmall 14sp / labelLarge 18sp。不要再用 Material 默认 16sp 标题。
- 灰阶对比：`TextSecondary #AEAEB2`、`TextTertiary #8E8E93`（#636366 在低亮度电视上不可读）；层次仍是 Primary > Secondary > Tertiary。
- 内容网格用 `GridCells.Adaptive(240.dp)`（1080p TV 常见 960×540dp，280dp 会只剩 2 列）；首页横排卡片宽 240dp。

## 首页结构

固定 TopBar（品牌 + provider + 「设置」「搜索」文字胶囊）→ 顶部分类标签 → 单分类大网格。**没有 Hero、没有「查看全部」、没有独立分类页**；老人用左右键在标签间盲操，停留即切换，不需要按 OK 瞄准某个入口。

- 分类标签来自 `HomeCategoryTabs`（`LazyRow` + `TvChip`）：焦点落上去 120ms 后自动 `selectTab`，快速划过不请求；当前标签用 `selected`（暗蓝），焦点态仍用 Apple Blue + 近白外框。首屏焦点落在当前分类标签，`HomeViewModel.consumeInitialFocus()` 只处理一次；标签 DOWN 指向网格首卡，首卡 UP 回当前标签。
- 首页一次只加载一个分类：`HomeViewModel` 按 provider+分类缓存第一页 10 分钟，切换分类命中缓存秒开；分页由 `PagedVideoGrid` 滚动触发。不要在首页预取全部分类——YouTube 分类是服务端实时 ytsearch，预取会成倍拉长首屏。
- 「继续观看」是第一个伪分类（`HomeContinueTabId`），数据来自本地 `WatchHistory`，不请求网络；卡片带进度条，点击直达上次分页。历史为空时显示空态并提示看过的视频会出现在这里。
- 空内容用 `EmptyStateView`（中性灰），**只有真实错误才用红色 `ErrorView`**；分类加载失败给重试。
- 分类/搜索统一用 `PagedVideoGrid`（`GridCells.Adaptive(240.dp)`，1080p TV 稳定 3 列、16:10 卡片、loading/重试 footer、焦点恢复），不要在页面里重复实现。
- 首页卡片标题 20sp、时长 15sp；页面分类标题用 `titleLarge` 22sp，数量用 `bodyLarge` 18sp。10ft 下不靠 14sp 以下的小字承载主信息。
- 纵向安全边距用 `ScreenVerticalPadding`（24dp）；播放器底栏底部至少 32dp，避免贴边被电视 overscan 裁掉。
- Provider 选择弹窗进入时聚焦当前源（`FocusRequester`），按 OK 不会误切到别的源。
- TV Button 默认焦点态对“本身就是 Apple Blue”的主按钮不可见：用 `ui/theme/TvButtonStyle.kt` 的 `tvPillButtonShape/Border/FocusScale`（近白 2dp 框 + 1.04x）；弹窗动作统一用 `TvDialogButton`，不要直接 TextButton。
- 图标用 `material-icons-core`（`Icons.Default.*` / `Icons.AutoMirrored.*`）；不要自绘 `ImageVector`（实测自绘 path 在 release 下不渲染）。Pause 用两根圆角竖条绘制。
- 搜索页单行 TextField 会消费方向键：**IME 打开时方向键归软键盘**，需 BACK 收起键盘；收起后 `SearchScreen` 显式处理 DOWN，聚焦首个热词/首个结果卡（并 hide 键盘），首卡的 UP 回输入框。
- 点热词后 chip 会被移出组合，焦点落回输入框会重新弹出键盘：加载态由结果区 `focusable` 容器承接焦点，结果到达再聚焦首卡；`SearchViewModel.completedQuery` 用于区分“仍在防抖/请求”与“已完成但零结果”，避免加载态无法结束。

## 播放页（老人遥控器契约）

播放状态与操作**不靠可聚焦按钮**：进度条只读、不可聚焦；播放/暂停/快退/快进/切集全部由根节点直接处理，避免“先移动焦点再按 OK”的多余步骤。

- **中央状态图标**：暂停时中央常驻 ▶ 三角形；恢复播放时短暂显示 ❚❚ 约 0.8s 后淡出，不长期遮挡画面。不要再用左上角“播放中/已暂停”文字或小圆点。
- 左上角只显示片名（32sp）与「第 X / Y 集」（22sp）；右上角「选集 · 清晰度」是触摸入口，电视端用 MENU 键打开同一个弹窗（`PlaybackOptionsDialog`，按 BACK/关闭退出）。
- 按键契约：←/→ 快退/快进 10 秒（长按连续）；↑/↓ 上一集/下一集（多P分页优先，其次合集；700ms 节流防长按连跳）；OK 播放/暂停（在 KeyUp 触发，避免长按连发反复切换）；BACK 先隐藏信息层，再退出。
- 底部信息层：只读进度条 + 当前/总时长（19sp）+ 五个大号键位提示（←/→/↑/↓/OK，15sp）+ 一行长按/MENU 说明。不要放上一集/下一集/±10 秒按钮，也不要让进度条抢焦点。
- 控制层隐藏时方向键仍然直接生效（先亮出信息层再执行动作）；控制层只在 `STATE_READY` 且正在播放时 4s 自动隐藏，缓冲时保持可见。
- 触摸：点画面中心播放/暂停，点左右两侧快退/快进；触摸点「选集 · 清晰度」打开选项弹窗。
- 自动连播仍由 `PlayerViewModel.onPlaybackEnded()` 决策；手动 ↑/↓ 走 `playPreviousManual()/playNextManual()`，合集上一集也发 `episodeEvents` 由 UI 导航。

## Legacy（API 19）对齐口径

- `media_app_legacy` 是 **API 19 原生 View/RecyclerView** 变体，不能引入 Compose（minSdk 21）或 Coil 2.x；新版视觉/交互要以同样的 tokens（#111 背景、CardGray、AppleBlue #007AFF、FocusBorder #E5E5EA）、带文字的操作胶囊、蓝色主按钮在旧技术栈里复刻。
- 首页同样只有顶部分类标签 + 单分类网格：固定 3 列 16:10 大卡片，卡片标题 20sp；**没有 Hero、时长筛选行、「查看全部」入口**。分类 chip 聚焦即切换；首次进入必须把焦点落在当前分类 chip（等真实分类到达后再标记完成，不能把 `__no_category__` 占位 chip 当成完成）。
- 详情页/播放页同样提供可见的「返回」文字入口；播放页中央状态图标由 `PlayPauseGlyphView` 自绘（暂停 ▶ / 播放 ❚❚），不要用 Unicode 字形。
- 播放页按键与新版一致：←/→ seek 10s（长按连续）、↑/↓ 直接切上一/下一集（不再双击确认）、OK 播放/暂停、MENU 切清晰度；控制层只在 `STATE_READY` 时自动隐藏，缓冲时保持可见。
- Legacy 的 provider 选择、YouTube 分类与焦点目标（Category/Provider/Settings/Search/Video）应与新版保持同一产品语义；旧设备低内存约束下不要为了“像新版”引入大图预加载/复杂动画。

## 组件与分层

- 通用控件只在 `ui/components/` 实现：`TvActionButton`（页面动作）、`TvChip`（可选/动作胶囊）、`TvDialogButton`（弹窗）、`TvIconButton`、`PagedVideoGrid`、`VideoCard`、`EmptyStateView`/`ErrorView`。页面不要再重复 Button 的 shape/border/scale/colors。
- ViewModel 依赖 `MediaRepositoryApi` 接口（单测注入 fake），不直接依赖具体 `MediaRepository`；磁盘历史读写走 IO 调度器（`HomeViewModel.refreshWatchHistory/registerPlayed`）。
- HTTP 统一用 `data/HttpCall.kt` 的 `Call.await()`，响应体读取放在 IO；不要在页面/VM 手写 OkHttp callback。
- Player 的 controller 连接（重试/释放幂等）在 `PlayerControllerConnection.kt`，音频焦点在 `PlayerAudioFocus.kt`；`PlayerContent` 只编排播放/控制条，不要继续膨胀。
