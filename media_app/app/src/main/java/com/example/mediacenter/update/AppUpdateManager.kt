package com.example.mediacenter.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.log.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateManager(
    private val context: Context,
    private val api: MediaApi,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun checkAndDownload(): AppUpdateResult {
        val update = try {
            api.appUpdate(BuildConfig.VERSION_CODE)
        } catch (error: Throwable) {
            AppLog.w("Update", "check failed: ${error.message}", error)
            return AppUpdateResult.Failed("检查更新失败，请稍后重试")
        } ?: return AppUpdateResult.NoUpdate

        if (update.versionCode <= BuildConfig.VERSION_CODE) return AppUpdateResult.NoUpdate
        AppLog.i("Update", "new version ${update.versionName} (${update.versionCode}) found")
        val apk = try {
            downloadAndVerify(update)
        } catch (error: Throwable) {
            AppLog.w("Update", "download failed: ${error.message}", error)
            return AppUpdateResult.Failed("下载更新失败，请稍后重试")
        }
        return AppUpdateResult.ReadyToInstall(update, apk)
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
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(settingsIntent)
    }

    fun launchInstaller(apk: File): InstallLaunchResult {
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
            AppLog.w("Update", "launch installer failed: ${error.message}", error)
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

sealed class InstallLaunchResult {
    object Started : InstallLaunchResult()
    object NeedsPermission : InstallLaunchResult()
    data class Failed(val message: String) : InstallLaunchResult()
}
