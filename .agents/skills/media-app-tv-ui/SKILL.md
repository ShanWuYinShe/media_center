---
name: media-app-tv-ui
description: media_app TV 界面专属设计约定：Apple TV 视觉基线、焦点态配色与外框、详情页简介折叠与披露弹层。触发：新增或修改 media_app 的 TV 界面、控件样式、焦点态样式、详情页与播放页布局。
---

# media_app TV UI/UX 设计约定（项目专属）

**通用约束**（可操作面积、长文本截断、图标与无障碍、系统级返回、焦点切换与焦点态语义）**见用户级 skill `mobile-ui-conventions`，本文件不复述**；以下只记本项目特有的视觉与交互设计。

- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为**唯一强调色**；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
- **TV 焦点态**：统一用 `ui/components` 的 `TvActionButton` / `TvChip` / `TvDialogButton` / `TvIconButton`（Apple Blue + `FocusBorder` #E5E5EA 2dp 外框 + 1.04x）；海报卡用 `VideoCard` 的 focusedBorder。`selected` 用暗蓝，只表达当前分类/选项，**不应强过焦点态**。
- **详情展开弹层**：详情页简介超出折叠行数时，用紧凑的「查看完整简介」披露按钮；完整简介在**独立 Dialog 阅读卡片**中展示，需保留 BACK 关闭路径与主按钮焦点，**不得在详情页原布局内撑开**。

## 排版与色彩

- 使用 `ui/theme/Theme.kt` 的 `TvTypography`（10ft 尺度）：headlineLarge 42sp / headlineMedium 32sp / titleLarge 22sp / titleMedium 19sp / bodyLarge 18sp / bodyMedium 16sp / bodySmall 14sp。不要再用 Material 默认 16sp 标题。
- 灰阶对比：`TextSecondary #AEAEB2`、`TextTertiary #8E8E93`（#636366 在低亮度电视上不可读）；层次仍是 Primary > Secondary > Tertiary。
- 内容网格用 `GridCells.Adaptive(240.dp)`（1080p TV 常见 960×540dp，280dp 会只剩 2 列）；首页横排卡片宽 240dp。

## 首页结构

TopBar（品牌标 + provider 胶囊 + 设置/搜索）→ Hero → 继续观看行 → 分类行。

- Hero：展示数据由 `HomeViewModel`/`buildHomeContent` 纯函数装配（UI 不做磁盘 IO/筛选）；有继续观看取最新一条，否则取首个有内容分类中**有封面**的第一条（避免首屏整块黑图）；无封面时用品牌蓝→底色的渐变兜底。精选 Hero 直接播放前由 `HomeViewModel.registerPlayed` 写观看历史（IO 线程），否则播放器退出回写找不到条目。
- 首页首屏焦点：等内容就绪后再 `requestFocus` 搜索入口（骨架屏切真实内容会重建 item，过早请求会丢焦落到设置图标）；是否已处理由 `HomeViewModel.consumeInitialFocus()` 记录，从详情/播放返回不抢焦点。搜索键的 DOWN 用 `focusProperties` 指向 Hero 播放按钮，避免 2D 焦点搜索优先命中横向更近的「详情」。
- 首次加载且无缓存时用 `HomeSkeleton`；已有内容时静默刷新，不铺骨架屏。
- 空内容用 `EmptyStateView`（中性灰 + 弱化播放图标），**只有真实错误才用红色 `ErrorView`**；分类筛选为空要给"查看全部"动作。
- 分类/搜索的统一分页海报网格用 `ui/components/PagedVideoGrid`（内置 `snapshotFlow { lastVisible to totalItemsCount }`、loading/重试 footer、焦点恢复），不要在页面里重复实现。
- 纵向安全边距用 `ScreenVerticalPadding`（24dp）；播放器底栏底部至少 32dp，避免 quality/分P 行贴边被电视 overscan 裁掉。
- Provider 选择弹窗进入时聚焦当前源（`FocusRequester`），按 OK 不会误切到别的源。
- TV Button 默认焦点态对"本身就是 Apple Blue"的主按钮不可见：用 `ui/theme/TvButtonStyle.kt` 的 `tvPillButtonShape/Border/FocusScale`（近白 2dp 框 + 1.04x）；弹窗动作统一用 `TvDialogButton`，不要直接 TextButton。
- 行标题右侧的「查看全部」默认焦点搜索不可达（上移会落到上一行卡片）：给行首卡片设置 `focusProperties { up = seeAllFocus }`。
- 图标用 `material-icons-core`（`Icons.Default.*` / `Icons.AutoMirrored.*`）；不要自绘 `ImageVector`（实测自绘 path 在 release 下不渲染）。Pause 不在 core 中，用两根圆角竖条绘制。
- 播放器控制条自动隐藏时必须把焦点交给根节点（`focusable` + `FocusRequester`），否则唯一可聚焦控件被移除后遥控器再也唤不出控制条。
- 搜索页单行 TextField 会消费方向键：**IME 打开时方向键归软键盘**，需 BACK 收起键盘；收起后 `SearchScreen` 显式处理 DOWN，聚焦首个热词/首个结果卡（并 hide 键盘），首卡的 UP 回输入框。
- 点热词后 chip 会被移出组合，焦点落回输入框会重新弹出键盘：加载态由结果区 `focusable` 容器承接焦点，结果到达再聚焦首卡；`SearchViewModel.completedQuery` 用于区分“仍在防抖/请求”与“已完成但零结果”，避免加载态无法结束。
- 控制条隐藏时按 OK 唤出：必须记录唤出键（`revealKey`），同时吞掉它的 KeyDown 与 KeyUp；只判断 `showControls` 不够——KeyUp 到达时它已变 true，会点击刚获得焦点的播放按钮把“唤出控制条”变成“暂停”。
- 自动连播：`STATE_ENDED` 只触发一次；多 P 下一 P 优先，最后一 P/单 P 走合集队列。决策在 `PlayerViewModel.onPlaybackEnded()`（Page 直接换页，Episode 发 `episodeEvents`），UI 只负责导航/登记历史；合集下一集替换当前播放页（`popUpTo(destination.id){inclusive=true}`），返回回到详情/首页。控制条在存在合集下一集时显示“下一集 · 标题”chip，支持手动跳过。

## 组件与分层

- 通用控件只在 `ui/components/` 实现：`TvActionButton`（页面动作）、`TvChip`（可选/动作胶囊）、`TvDialogButton`（弹窗）、`TvIconButton`、`PagedVideoGrid`、`VideoCard`、`EmptyStateView`/`ErrorView`。页面不要再重复 Button 的 shape/border/scale/colors。
- ViewModel 依赖 `MediaRepositoryApi` 接口（单测注入 fake），不直接依赖具体 `MediaRepository`；磁盘历史读写走 IO 调度器（`HomeViewModel.refreshWatchHistory/registerPlayed`）。
- HTTP 统一用 `data/HttpCall.kt` 的 `Call.await()`，响应体读取放在 IO；不要在页面/VM 手写 OkHttp callback。
- Player 的 controller 连接（重试/释放幂等）在 `PlayerControllerConnection.kt`，音频焦点在 `PlayerAudioFocus.kt`；`PlayerContent` 只编排播放/控制条，不要继续膨胀。
