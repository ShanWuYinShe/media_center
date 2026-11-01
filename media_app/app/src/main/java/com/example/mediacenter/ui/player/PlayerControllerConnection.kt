package com.example.mediacenter.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.mediacenter.player.PlaybackService

/**
 * 与 PlaybackService 的 MediaController 连接状态。
 * - failed: 重试上限内仍未连上,UI 展示错误 + 重试;
 * - retry: 重连(重置失败态与尝试次数);
 * - release: 页面退出时释放当前 controller(幂等)。
 */
internal class PlaybackControllerState(
    val controller: MediaController?,
    val failed: Boolean,
    val retry: () -> Unit,
    val release: () -> Unit,
)

/**
 * 建立/重试 MediaController 连接。
 *
 * 从 PlayerContent 拆出:连接重试、future 取消释放与 release 幂等这类
 * 生命周期细节不再和播放 UI/控制条逻辑混在一个 500+ 行 composable 里。
 */
@Composable
internal fun rememberPlaybackController(context: Context): PlaybackControllerState {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    val releaseGuard = remember { AtomicBoolean(false) }

    fun releaseOnce(c: MediaController?) {
        if (c != null && releaseGuard.compareAndSet(false, true)) {
            c.release()
        }
    }

    LaunchedEffect(attempts) {
        if (attempts > 0) {
            delay(CONTROLLER_RETRY_DELAY_MS)
        }
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        // 连接 future 可能被 onReady 与协程取消两条路径同时消费,释放也必须幂等。
        val attemptReleaseGuard = AtomicBoolean(false)
        fun releaseAttempt(c: MediaController?) {
            if (c != null && attemptReleaseGuard.compareAndSet(false, true)) {
                c.release()
            }
        }
        val connected = try {
            suspendCancellableCoroutine<MediaController?> { continuation ->
                val onReady = {
                    val mediaController = runCatching { future.get() }.getOrNull()
                    if (continuation.isActive) {
                        continuation.resume(mediaController)
                    } else {
                        // 页面已退出(协程取消),连接结果无人消费,立即释放。
                        releaseAttempt(mediaController)
                    }
                    Unit
                }
                continuation.invokeOnCancellation {
                    if (future.isDone) {
                        runCatching { releaseAttempt(future.get()) }
                    }
                }
                future.addListener(onReady, Executor { command -> command.run() })
            }
        } catch (e: Exception) {
            null
        }
        if (connected != null) {
            // 新 controller 接管:重新武装释放守卫。
            releaseGuard.set(false)
            controller = connected
            failed = false
        } else if (attempts < MAX_CONTROLLER_ATTEMPTS) {
            attempts++
        } else {
            failed = true
        }
    }

    return PlaybackControllerState(
        controller = controller,
        failed = failed,
        retry = {
            failed = false
            attempts = 0
        },
        release = { releaseOnce(controller) },
    )
}
