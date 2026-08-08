package com.example.mediacenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.mediacenter.log.AppLog
import java.io.File
import java.io.FileInputStream

/**
 * APK 安装器:从 AppUpdateManager 拆分。
 *
 * 依次尝试三种安装方式(兼容国产 TV):
 *  1. PackageInstaller API(API 21+,session-based,最可靠)
 *  2. Intent.ACTION_INSTALL_PACKAGE(部分国产 TV 支持但无 PackageInstaller)
 *  3. Intent.ACTION_VIEW + "安装未知应用"权限引导(兜底)
 *
 * 安装结果通过 broadcast 回调记录日志;权限引导失败时降级到应用详情页。
 */
class ApkInstaller(private val context: Context) {
    private var installReceiver: BroadcastReceiver? = null
    private val installAction = "${context.packageName}.INSTALL_RESULT"

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
        // ACTION_MANAGE_UNKNOWN_APP_SOURCES 是 API 26 常量,用字符串字面量避免 lint
        // InlinedApi;runCatching 兜底:不支持时降级到应用详情页。
        val primaryIntent = Intent(
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            "package:${context.packageName}".toUri(),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(primaryIntent)
        }.onFailure {
            AppLog.w("Update", "launchInstallPermissionSettings: redirect to app details", it)
            val fallbackIntent = Intent(
                "android.settings.APPLICATION_DETAILS_SETTINGS",
                "package:${context.packageName}".toUri(),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun launchInstaller(apk: File): InstallLaunchResult {
        // 第1优先：PackageInstaller API（API 21+），session-based 安装。
        // minSdk=23,该守卫恒为真,但保留以兼容将来 minSdk 调整时 PackageInstaller 的语义边界。
        val result = tryInstallWithSession(apk)
        if (result is InstallLaunchResult.Started) return result
        AppLog.w("Update", "PackageInstaller session failed")
        // 第2优先：Intent.ACTION_INSTALL_PACKAGE。国产 TV 上比 ACTION_VIEW 更兼容。
        val installResult = tryInstallWithInstallPackage(apk)
        if (installResult is InstallLaunchResult.Started) return installResult
        AppLog.w("Update", "ACTION_INSTALL_PACKAGE failed")
        // 第3优先（回退）：Intent.ACTION_VIEW + 权限弹窗引导。
        return tryInstallWithIntent(apk)
    }

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
}
