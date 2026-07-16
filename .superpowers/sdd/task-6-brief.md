### Task 6: DetailScreen + SearchScreen + UpdateDialog

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/detail/DetailScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/detail/DescriptionDialog.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/search/SearchScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/search/SearchViewModel.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/update/UpdateDialog.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/components/ErrorView.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/components/LoadingIndicator.kt`
- Modify: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt`

- [ ] **Step 1–N: 各 Screen 实现**

具体代码从旧 MainActivity 的对应 UI 逻辑提取，转换为 Compose。

DetailScreen 关键规则：
- `BackHandler` 处理返回
- 简介用折叠 + "查看完整简介" Dialog
- 播放按钮用 `requiredSize` 保证 D-pad 可操作
- Coil 显示缩略图

SearchScreen：
- TextField 输入框 + LazyColumn 搜索结果
- 使用 `maxLines` + Ellipsis 防止长文本挤压

UpdateDialog：
- 使用 Compose AlertDialog
- 下载进度用 LinearProgressIndicator

- [ ] **Final: 编译验证 + Commit**

---

