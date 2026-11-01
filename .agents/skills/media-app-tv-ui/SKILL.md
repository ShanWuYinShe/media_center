---
name: media-app-tv-ui
description: media_app TV 界面专属设计约定：Apple TV 视觉基线、焦点态配色与外框、详情页简介折叠与披露弹层。触发：新增或修改 media_app 的 TV 界面、控件样式、焦点态样式、详情页与播放页布局。
---

# media_app TV UI/UX 设计约定（项目专属）

**通用约束**（可操作面积、长文本截断、图标与无障碍、系统级返回、焦点切换与焦点态语义）**见用户级 skill `mobile-ui-conventions`，本文件不复述**；以下只记本项目特有的视觉与交互设计。

- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为**唯一强调色**；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
- **TV 焦点态**：焦点态统一使用 Apple Blue 背景 + 白色文字/图标 + `FocusBorder`（#E5E5EA）2dp 近白外框（`GlassBorder.kt` 的 `glassCardFocused()`）；`selected` 只表达当前分类/选项，**不应强过焦点态**。
- **详情展开弹层**：详情页简介超出折叠行数时，用紧凑的「查看完整简介」披露按钮；完整简介在**独立 Dialog 阅读卡片**中展示，需保留 BACK 关闭路径与主按钮焦点，**不得在详情页原布局内撑开**。

## 排版与色彩（2026-11-01 起）

- 使用 `ui/theme/Theme.kt` 的 `TvTypography`（10ft 尺度）：headlineLarge 42sp / headlineMedium 32sp / titleLarge 22sp / titleMedium 19sp / bodyLarge 18sp / bodyMedium 16sp / bodySmall 14sp。不要再用 Material 默认 16sp 标题。
- 灰阶对比：`TextSecondary #AEAEB2`、`TextTertiary #8E8E93`（原 #636366 在低亮度电视上不可读）；层次仍是 Primary > Secondary > Tertiary。
- 内容网格用 `GridCells.Adaptive(240.dp)`（1080p TV 常见 960×540dp，280dp 会只剩 2 列）；首页横排卡片宽 240dp。

## 首页结构

TopBar（品牌标 + provider 胶囊 + 设置/搜索）→ Hero → 继续观看行 → 分类行。

- Hero：有继续观看时取最新一条（带进度、主按钮"继续播放"），否则取首个有内容分类的第一条（"精选"）；高 360dp，双层遮罩（左压暗 + 底部融进背景），主/次按钮"播放/详情"。精选 Hero 直接播放前要 `WatchHistory.upsert`，否则播放器退出回写找不到条目。
- 首次加载且无缓存时用 `HomeSkeleton`；已有内容时静默刷新，不铺骨架屏。
- 空内容用 `EmptyStateView`（中性灰 + 弱化播放图标），**只有真实错误才用红色 `ErrorView`**；分类筛选为空要给"查看全部"动作。
- 长列表分页触发用 `snapshotFlow { lastVisible to totalItemsCount }`；只观察 lastVisible 会在新页填满可视区时卡住。
- TV Button 默认焦点态对"本身就是 Apple Blue"的主按钮不可见：用 `ui/theme/TvButtonStyle.kt` 的 `tvPillButtonShape/Border/FocusScale`（近白 2dp 框 + 1.04x）；弹窗动作统一用 `TvDialogButton`，不要直接 TextButton。
- 行标题右侧的「查看全部」默认焦点搜索不可达（上移会落到上一行卡片）：给行首卡片设置 `focusProperties { up = seeAllFocus }`。
- 图标用 `material-icons-core`（`Icons.Default.*` / `Icons.AutoMirrored.*`）；不要自绘 `ImageVector`（实测自绘 path 在 release 下不渲染）。Pause 不在 core 中，用两根圆角竖条绘制。
- 播放器控制条自动隐藏时必须把焦点交给根节点（`focusable` + `FocusRequester`），否则唯一可聚焦控件被移除后遥控器再也唤不出控制条。
- 自动连播：`STATE_ENDED` 只触发一次；多 P 的下一 P 优先，最后一 P/单 P 再走合集队列。合集下一集用导航替换当前播放页（`popUpTo(destination.id){inclusive=true}`），返回时回到详情/首页；直达下一集前要补 `WatchHistory.upsert`。
