---
name: media-app-tv-ui
description: media_app TV 界面专属设计约定：Apple TV 视觉基线、焦点态配色与外框、详情页简介折叠与披露弹层。触发：新增或修改 media_app 的 TV 界面、控件样式、焦点态样式、详情页与播放页布局。
---

# media_app TV UI/UX 设计约定（项目专属）

**通用约束**（可操作面积、长文本截断、图标与无障碍、系统级返回、焦点切换与焦点态语义）**见用户级 skill `mobile-ui-conventions`，本文件不复述**；以下只记本项目特有的视觉与交互设计。

- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为**唯一强调色**；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
- **TV 焦点态**：焦点态统一使用 Apple Blue 背景 + 白色文字/图标 + `FocusBorder`（#E5E5EA）2dp 近白外框（`GlassBorder.kt` 的 `glassCardFocused()`）；`selected` 只表达当前分类/选项，**不应强过焦点态**。
- **详情展开弹层**：详情页简介超出折叠行数时，用紧凑的「查看完整简介」披露按钮；完整简介在**独立 Dialog 阅读卡片**中展示，需保留 BACK 关闭路径与主按钮焦点，**不得在详情页原布局内撑开**。
