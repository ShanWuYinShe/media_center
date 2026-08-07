package com.example.mediacenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.friendlyNetworkMessage
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateManager(
    private val context: Context,
    private val api: MediaApi,
    private val client: OkHttpClient = AppHttpClient.shared,
) {
    private var installReceiver: BroadcastReceiver? = null
    private val installAction = "${context.packageName}.INSTALL_RESULT"
    suspend fun checkAndDownload(): AppUpdateResult {
        // 检查阶段自动重试:老 TV 盒子上 TLS/网络抖动常见,单次失败直接弹
        // "检查更新失败"没有意义。最多 CHECK_MAX_ATTEMPTS 次,退避见
        // CHECK_RETRY_BACKOFF_MS;仍失败时用确定性分类给出根因文案。
        var lastError: Throwable? = null
        for (attempt in 0 until CHECK_MAX_ATTEMPTS) {
            try {
                val update = api.appUpdate(BuildConfig.VERSION_CODE)
                if (update == null) return AppUpdateResult.NoUpdate
                if (update.versionCode <= BuildConfig.VERSION_CODE) return AppUpdateResult.NoUpdate
                AppLog.i("Update", "new version ${update.versionName} (${update.versionCode}) found")
                val apk = try {
                    downloadAndVerify(update)
                } catch (error: Throwable) {
                    lastError = error
                    if (attempt < CHECK_MAX_ATTEMPTS - 1) {
                        AppLog.w("Update", "download failed, retry ${attempt + 1}/$CHECK_MAX_ATTEMPTS: ${error.message}", error)
                        delay(CHECK_RETRY_BACKOFF_MS[attempt])
                        continue
                    }
                    AppLog.w("Update", "download failed: ${error.message}", error)
                    return AppUpdateResult.Failed("下载更新失败：${friendlyNetworkMessage(error)}")
                }
                return AppUpdateResult.ReadyToInstall(update, apk)
            } catch (error: Throwable) {
                lastError = error
                if (attempt < CHECK_MAX_ATTEMPTS - 1) {
                    AppLog.w("Update", "check failed, retry ${attempt + 1}/$CHECK_MAX_ATTEMPTS: ${error.message}", error)
                    delay(CHECK_RETRY_BACKOFF_MS[attempt])
                    continue
                }
            }
        }
        AppLog.w("Update", "check failed after $CHECK_MAX_ATTEMPTS attempts", lastError)
        return AppUpdateResult.Failed("检查更新失败：${friendlyNetworkMessage(lastError)}")
    }

    private suspend fun downloadAndVerify(update: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val target = File(dir, "media-center-${update.versionCode}.apk")
        if (target.isFile &&
            target.length() == update.sizeBytes &&
            target.sha256().equals(update.sha256, ignoreCase = true)
        ) {
            return@withContext target
        }

        val request = Request.Builder()
            .url(update.apkUrl)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("download returned ${response.code()}")
            }
            val body = response.body() ?: throw IllegalStateException("empty apk response")
            FileOutputStream(target).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        if (target.length() != update.sizeBytes) {
            target.delete()
            throw IllegalStateException("apk size mismatch")
        }
        if (!target.sha256().equals(update.sha256, ignoreCase = true)) {
            target.delete()
            throw IllegalStateException("apk checksum mismatch")
        }
        target
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun launchInstallPermissionSettings() {
        // 国产 TV 可能不支持 ACTION_MANAGE_UNKNOWN_APP_SOURCES，降级到应用详情页。
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
            // FLAG_IMMUTABLE 是 API 23+ 才有的常量;在 API 21/22 上引用会 NoSuchFieldError。
            // 本方法入口守卫是 >= LOLLIPOP(API 21),因此必须按版本选择标志。
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent, pendingFlags,
            )

            installReceiver?.let { context.unregisterReceiver(it) }
            installReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    AppLog.i("Update", "install result: status=$status")
                    ctx.unregisterReceiver(this)
                    installReceiver = null
                }
            }
            // ContextCompat 统一处理 Android 13+ 的 RECEIVER_NOT_EXPORTED 要求(兼容 minSdk 19)。
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

sealed class AppUpdateResult {
    object NoUpdate : AppUpdateResult()
    data class ReadyToInstall(val update: AppUpdateInfo, val apk: File) : AppUpdateResult()
    data class Failed(val message: String) : AppUpdateResult()
}

private const val CHECK_MAX_ATTEMPTS = 3
private val CHECK_RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L)

sealed class InstallLaunchResult {
    object Started : InstallLaunchResult()
    object NeedsPermission : InstallLaunchResult()
    data class Failed(val message: String) : InstallLaunchResult()
}
