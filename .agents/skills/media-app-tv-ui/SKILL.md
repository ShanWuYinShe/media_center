---
name: media-app-tv-ui
description: media_app TV UI/UX 设计约定。触发：新增或修改 TV 界面、控件样式、焦点逻辑、详情页与播放页时加载。
---

# media_app TV UI/UX 设计约定

## UI/UX 设计约定

- 固定交互控件尺寸：按钮、chip、圆形 icon button 等可聚焦/可点击控件使用 `requiredHeight` / `requiredSize` 保证可操作面积，不用普通 `height` / `size` 让父布局压缩。
- 长文本不可挤压操作区：标题、简介、错误文案、分类名、按钮文案必须设置 `maxLines` 和 `TextOverflow.Ellipsis`。详情页这类"文案 + 主操作"布局中，主操作区优先保留空间；长简介先折叠，超出后用"查看详情"弹层展示完整内容，不能在原布局内无限展开。
- 详情展开弹层：详情页简介超出折叠行数时使用紧凑"查看完整简介"披露按钮；完整简介在独立 Dialog 阅读卡片中展示，保留 BACK 关闭路径和主按钮焦点，避免在详情页原布局内撑开。
- 图标风格统一：播放、暂停、搜索、清除、箭头等图标使用 Canvas/vector/组件绘制，不把 `▶`、`⏸`、emoji 等 Unicode 字符当图标拼进 `Text`，避免 Android 字体 fallback 导致风格不一致。
- TV 焦点态一致：焦点态统一使用 Apple Blue 背景、白色文字/图标、`GlassBorderFocused` 近白外框；selected 只表达当前分类/选项，不应强过焦点态。焦点切换应使用明确的 `FocusRequester` / `focusProperties` 路径，避免依赖空间猜测。
- Back 行为稳定：详情页、播放页、搜索面板等屏幕级返回使用 `BackHandler`，不要依赖子组件焦点或 `onPreviewKeyEvent` 捕获 Back。
- Apple TV 视觉基线：OLED 深色背景、玻璃灰卡片、Apple Blue 作为唯一强调色；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。
