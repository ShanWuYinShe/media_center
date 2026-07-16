### Task 7: 清理旧 View 代码 + 依赖

**Files:**
- Delete: `media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt`（旧 View 版）
- Modify: `media_app/app/src/main/java/com/example/mediacenter/app/build.gradle.kts` — 移除 recyclerview + media3-ui（旧代码已清理完毕，可以安全删除）
- Delete: 旧布局文件/资源（如果有，检查 `res/layout/` 目录）

- [ ] **Step 1: 删除旧 MainActivity.kt（所有可提取的非 UI 代码已在 Task 3 移出）**

```bash
rm media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt
```

- [ ] **Step 2: 验证无残留 `android.view.*` 或 `android.widget.*` import**

```bash
rg "import android\.(view|widget)" media_app/app/src/main/java/ --type kotlinc
```
Expected: 0 matches

- [ ] **Step 3: 完整构建验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL in Xs

- [ ] **Step 4: 检查 APK 产物**

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Commit**

```bash
git rm media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt
git add -A
git commit -m "refactor(media_app): 清理旧 View 代码，Compose 重构完成"
```

---

### 自审清单

1. **Spec 覆盖**: 每项 AGENTS.md 中的技术栈要求（Compose、TV Material、Coil、Media3）都有对应 Task；Apple TV 视觉基线在 Theme.kt 中实现；焦点态/BackHandler/长文本等 UI/UX 约定在各 Screen 中落地。
2. **Placeholder 扫描**: 无 TBD/placeholder，每个文件路径和代码段完整。
3. **类型一致性**: `Routes` 对象在 AppNavigation.kt 定义，所有 Screen 通过 `navController.navigate(Routes.detail(id))` 调用。
