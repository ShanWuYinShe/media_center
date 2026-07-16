# Task 5: PlayerScreen — Report

**Status**: DONE

**Commit**: `ac34ac4`

**Build**: `BUILD SUCCESSFUL` (1 pre-existing warning in MediaViewModel.kt, unrelated)

## Files created/modified

- Created `ui/player/PlayerViewModel.kt` — `PlayerUiState`, `PlayerViewModel` (resolves playback via `MediaRepository` + `BilibiliApi`), `PlayerViewModelFactory`
- Created `ui/player/PlayerScreen.kt` — `PlayerScreen` composable with `BackHandler`, state-driven UI (loading/error/player), `ExoPlayerPlayerView` helper wrapping `ExoPlayer` (with `CappedLoadErrorHandlingPolicy`) + `PlayerView` via `AndroidView`
- Modified `ui/AppNavigation.kt` — registered `composable(Routes.PLAYER)` route extracting `videoId` from nav arguments

## Key adjustments from spec

1. **`PlaybackResult.options`** (actual model field) instead of spec's `playbackOptions`
2. **`CappedLoadErrorHandlingPolicy(maxRetries = 2)`** — required parameter not in spec sample
3. **`MediaRepository(api = MediaApi(BuildConfig.MEDIA_SERVER_URL))`** — followed `HomeViewModel` pattern; spec's `MediaRepository()` doesn't compile
4. **Player lifecycle** — `remember` + `DisposableEffect(onDispose = { player.release() })` inside extracted `ExoPlayerPlayerView` composable, avoiding spec's buggy inline `DisposableEffect`

## Concerns

- Player only uses first `PlaybackOption` URL; multi-quality/pages not surfaced in UI yet
- Player is opaque full-screen without controls customization — `PlayerView` defaults apply
