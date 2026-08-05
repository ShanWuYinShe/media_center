package com.example.mediacenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settingsimport androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * App 内更新:检查(查版本)→ 用户确认 → 下载(带进度)→ 校验 SHA256 → 启动系统安装器。
 * 下载前不弹窗确认;版本检查与下载分离,由调用方(UI)控制流程。
 *
 * 并发安全:同一版本的下载由 [downloadMutex] 串行化——重复触发不会并发写同一个
 * 文件;下载使用可取消的 OkHttp 调用,协程取消时中断请求并清理半成品文件。
 */
class AppUpdateManager(
    private val context: Context,
    private val api: MediaApi,
    private val client: OkHttpClient = AppHttpClient.shared,
) {
    private var installReceiver: BroadcastReceiver? = null
    private val installAction = "${context.packageName}.INSTALL_RESULT"
    private val downloadMutex = Mutex()

    /** 第一步:检查是否有新版本,只发网络请求,不下载。 */
    suspend fun check(): UpdateCheckResult {
        val update = try {
            api.appUpdate(BuildConfig.VERSION_CODE)
        } catch (error: Throwable) {
            AppLog.w("Update", "check failed: ${error.message}", error)
            return UpdateCheckResult.Failed("检查更新失败，请稍后重试")
        }
        return if (update == null || update.versionCode <= BuildConfig.VERSION_CODE) {
            UpdateCheckResult.NoUpdate
        } else {
            AppLog.i("Update", "new version ${update.versionName} (${update.versionCode}) found")
            UpdateCheckResult.UpdateAvailable(update)
        }
    }

    /** 第二步:下载并校验更新包,onProgress 回调 0f..1f 进度。 */
    suspend fun download(
        update: AppUpdateInfo,
        onProgress: (Float) -> Unit = {},
    ): UpdateDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val apk = downloadAndVerify(update, onProgress)
                UpdateDownloadResult.Ready(update, apk)
            } catch (error: CancellationException) {
                // 协程取消(离开下载流程):删除半成品,向上传播取消,不当作失败弹窗
                cleanupPartialDownload(update)
                throw error
            } catch (error: Throwable) {
                AppLog.w("Update", "download failed: ${error.message}", error)
                UpdateDownloadResult.Failed("下载更新失败，请稍后重试")
            }
        }
    }

    private fun cleanupPartialDownload(update: AppUpdateInfo) {
        val target = apkTargetFile(update)
        if (target.isFile) target.delete()
    }

    /** apkUrl 的 host 必须与 API 基址 host 一致;不一致视为投毒,拒绝下载。 */
    private fun validateApkUrlHost(update: AppUpdateInfo) {
        val expectedHost = runCatching { Uri.parse(api.baseUrl).host }.getOrNull()
        if (expectedHost.isNullOrEmpty()) return
        val apkHost = runCatching { Uri.parse(update.apkUrl).host }.getOrNull()
        if (apkHost != null && !apkHost.equals(expectedHost, ignoreCase = true)) {
            AppLog.w("Update", "refusing apk download: host $apkHost != api host $expectedHost")
            throw IllegalStateException("apk url host mismatch")
        }
    }

    private fun apkTargetFile(update: AppUpdateInfo): File =
        File(File(context.cacheDir, "app-updates"), "media-center-${update.versionCode}.apk")

    private suspend fun downloadAndVerify(update: AppUpdateInfo, onProgress: (Float) -> Unit): File {
        // 同源校验:manifest.apkUrl 的 host 必须与 API 基址一致。服务端即使被
        // Host 头投毒(未配置 MEDIA_SERVER_PUBLIC_URL 时),客户端也拒绝下载
        // 指向其他域名的 APK,切断供应链投毒链。
        validateApkUrlHost(update)

        val target = apkTargetFile(update)
        if (target.isFile &&
            target.length() == update.sizeBytes &&
            target.sha256().equals(update.sha256, ignoreCase = true)
        ) {
            onProgress(1f)
            return target
        }

        val request = Request.Builder()
            .url(update.apkUrl)
            .get()
            .build()
        // 可取消下载:协程取消时 call.cancel() 中断请求与流拷贝。
        // 进度回调按节流间隔上报,避免 8KB chunk 粒度下高频重组 UI。
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                throw IllegalStateException("download returned ${it.code}")
                            }
                            val body = it.body
                            val totalBytes = body.contentLength()
                            var copied = 0L
                            var lastReportMs = 0L
                            FileOutputStream(target).use { out ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        out.write(buffer, 0, read)
                                        copied += read
                                        val nowMs = System.currentTimeMillis()
                                        if (totalBytes > 0 && nowMs - lastReportMs >= PROGRESS_THROTTLE_MS) {
                                            lastReportMs = nowMs
                                            onProgress((copied.toFloat() / totalBytes).coerceIn(0f, 1f))
                                        }
                                    }
                                }
                            }
                            if (totalBytes > 0) onProgress(1f)
                        }
                        // 尺寸 + SHA256 双校验;失败删除半成品
                        if (target.length() != update.sizeBytes) {
                            target.delete()
                            throw IllegalStateException("apk size mismatch")
                        }
                        if (!target.sha256().equals(update.sha256, ignoreCase = true)) {
                            target.delete()
                            throw IllegalStateException("apk checksum mismatch")
                        }
                        if (continuation.isActive) continuation.resume(target)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    /** Android 8+ 需要"安装未知应用"权限才能用 ACTION_VIEW 装 APK。 */
    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /**
     * 引导用户开启"安装未知应用"权限。国产 TV 可能不支持
     * ACTION_MANAGE_UNKNOWN_APP_SOURCES,此时降级到应用详情页。
     */
    fun launchInstallPermissionSettings() {
        val primaryIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(primaryIntent)
        }.onFailure {
            AppLog.w("Update", "launchInstallPermissionSettings: redirect to app details", it)
            val fallbackIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun launchInstaller(apk: File): InstallLaunchResult {
        // 第1优先：PackageInstaller API（API 21+），session-based 安装。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val result = tryInstallWithSession(apk)
            if (result is InstallLaunchResult.Started) return result
            AppLog.w("Update", "PackageInstaller session failed")
        }
        // 第2优先：Intent.ACTION_INSTALL_PACKAGE。国产 TV 上比 ACTION_VIEW 更兼容。
        val result = tryInstallWithInstallPackage(apk)
        if (result is InstallLaunchResult.Started) return result
        AppLog.w("Update", "ACTION_INSTALL_PACKAGE failed")
        // 第3优先（回退）：Intent.ACTION_VIEW + 权限弹窗引导。
        return tryInstallWithIntent(apk)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun tryInstallWithSession(apk: File): InstallLaunchResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            FileInputStream(apk).use { input ->
                val size = apk.length()
                val out = session.openWrite("base.apk", 0, size)
                input.copyTo(out)
                session.fsync(out)
                out.close()
            }

            val intent = Intent(installAction).apply {
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            installReceiver?.let { context.unregisterReceiver(it) }
            installReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    @Suppress("InlinedApi")
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    AppLog.i("Update", "install result: status=$status")
                    ctx.unregisterReceiver(this)
                    installReceiver = null
                }
            }
            ContextCompat.registerReceiver(
                context, installReceiver, IntentFilter(installAction),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            InstallLaunchResult.Started
        } catch (error: Throwable) {
            AppLog.w("Update", "PackageInstaller session failed: ${error.message}", error)
            InstallLaunchResult.Failed("安装失败：${error.message}")
        }
    }

    /**
     * 用 Intent.ACTION_INSTALL_PACKAGE 启动安装器。部分国产 TV 不支持
     * PackageInstaller API 但支持此 intent（比 ACTION_VIEW 兼容性更广）。
     */
    private fun tryInstallWithInstallPackage(apk: File): InstallLaunchResult {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.Started
        } catch (error: Throwable) {
            AppLog.w("Update", "ACTION_INSTALL_PACKAGE failed: ${error.message}", error)
            InstallLaunchResult.Failed("安装失败：${error.message}")
        }
    }

    private fun tryInstallWithIntent(apk: File): InstallLaunchResult {
        // Android 8+ 无"安装未知应用"权限时 ACTION_VIEW 会静默失败,先引导开权限。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallLaunchResult.NeedsPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            InstallLaunchResult.Started
        } catch (error: Throwable) {
            AppLog.w("Update", "ACTION_VIEW install failed: ${error.message}", error)
            InstallLaunchResult.Failed("无法打开系统安装器，请手动安装更新包")
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed class UpdateCheckResult {
    object NoUpdate : UpdateCheckResult()
    data class UpdateAvailable(val update: AppUpdateInfo) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

private const val PROGRESS_THROTTLE_MS = 100L

sealed class UpdateDownloadResult {
    data class Ready(val update: AppUpdateInfo, val apk: File) : UpdateDownloadResult()
    data class Failed(val message: String) : UpdateDownloadResult()
}

sealed class InstallLaunchResult {
    object Started : InstallLaunchResult()
    /** 需要用户先去系统设置开启"安装未知应用"权限,用 launchInstallPermissionSettings 引导。 */
    object NeedsPermission : InstallLaunchResult()
    data class Failed(val message: String) : InstallLaunchResult()
}
