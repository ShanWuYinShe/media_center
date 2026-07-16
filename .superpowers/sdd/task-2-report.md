# Task 2 Report: 主题体系 + ComposeActivity + 导航骨架

## Files Created
- `media_app/app/src/main/java/com/example/mediacenter/ui/theme/Color.kt` — Apple TV color palette (11 colors)
- `media_app/app/src/main/java/com/example/mediacenter/ui/theme/GlassBorder.kt` — `glassCardBackground()` and `glassCardFocused()` modifiers
- `media_app/app/src/main/java/com/example/mediacenter/ui/theme/Theme.kt` — `AppleTvColors` data class, `LocalAppleTvColors` CompositionLocal, `MediaCenterTheme` composable
- `media_app/app/src/main/java/com/example/mediacenter/ComposeActivity.kt` — New Compose entry point
- `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt` — Navigation skeleton with `Routes` object and `NavHost`

## Files Modified
- `media_app/app/src/main/AndroidManifest.xml` — Added `.ComposeActivity` as launcher (exported, MAIN/LAUNCHER/LEANBACK_LAUNCHER), changed `.MainActivity` to `exported="false"`

## Build Result
- `BUILD SUCCESSFUL` after fixing missing `Color` import in Theme.kt

## Commit
- `dfacd868f9cdf209f207a7247f7330f505f4ea97`

## Concerns
- Theme.kt uses `androidx.compose.material3.MaterialTheme` directly — ensures basic theming works but TV Material's own theme wrapper may be preferred later
- Brief had two "Step 5"s; used the correct step ordering from the checklist
