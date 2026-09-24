---
name: media-app-tv-ui
description: media_app TV 界面专属设计约定：Apple TV 视觉基线、焦点态配色与外框、详情页简介折叠与披露弹层、TV 尺寸档位。触发：新增或修改 media_app 的 TV 界面、控件样式、焦点态样式、详情页与播放页布局。
---

# media_app TV UI/UX 设计约定（项目专属）

**通用约束**（可操作面积用 `requiredHeight`/`requiredSize` 不被父布局压缩、长文本 `maxLines`+`TextOverflow.Ellipsis` 且不挤压主操作区、图标不用 Unicode 字符、屏幕级返回用 `BackHandler`、焦点切换用 `FocusRequester`/`focusProperties` 而非空间猜测、焦点态与选中态语义分离）**见用户级 skill `mobile-ui-conventions`，本文件不复述。**

以下为本项目特有的视觉与交互设计：

- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为**唯一强调色**；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
- **TV 焦点态**：焦点态统一使用 Apple Blue 背景 + 白色文字/图标 + `GlassBorderFocused` 近白外框；`selected` 只表达当前分类/选项，**不应强过焦点态**。
- **详情展开弹层**：详情页简介超出折叠行数时，用紧凑的「查看完整简介」披露按钮；完整简介在**独立 Dialog 阅读卡片**中展示，需保留 BACK 关闭路径与主按钮焦点，**不得在详情页原布局内撑开**。
