package com.example.mediacenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.mediacenter.log.AppLog
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
class ApkInstaller(
    private val context: Context,
    // STATUS_PENDING_USER_ACTION 时的确认 Intent 回调:**必须由前台 Activity 启动**。
    // Android 9+ 的后台 Activity 启动限制会静默拦截 receiver 里的 startActivity
    // (模拟器实测:无异常、无 ATM START 记录、对话框不出现),所以不能在 receiver
    // 里直接启动;交给可见的 Activity 启动即豁免。
    private val onPendingUserAction: (Intent) -> Unit,
    // 安装最终失败的用户可读原因回调。PackageInstaller session 被系统拒绝时,
    // 先尝试一次 ACTION_INSTALL_PACKAGE 兼容回退(由前台 Activity 启动);
    // 回退也无法发起(如未允许安装未知应用)或没有兜底路径时才回调这里,
    // 由 AppUpdateHolder 弹出「安装失败」+ 重试,不再静默停住。
    private val onInstallFailed: (String) -> Unit = {},
) {
    private var installReceiver: BroadcastReceiver? = null
    // 本次安装的 APK:session 失败时要拿它发起兼容回退,因此随 commit 一起记住。
    private var pendingApk: File? = null
    // 回退只尝试一次:失败结果会再次进 receiver,避免无限递归。
    private var fallbackAttempted = false
    private val installAction = "${context.packageName}.INSTALL_RESULT"
    // 超时注销用主线程 Handler:BroadcastReceiver 注销本身线程安全,排期统一走主线程
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverTimeout: Runnable? = null

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

    suspend fun launchInstaller(apk: File): InstallLaunchResult {
        pendingApk = apk
        fallbackAttempted = false
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

    private suspend fun tryInstallWithSession(apk: File): InstallLaunchResult {
        // session 兜底:复制 APK 抛异常/协程取消时 abandon 掉会话——否则失败的
        // 会话残留在系统 PackageInstaller 里(国产 TV 会话配额有限,累积会导致
        // 后续更新全部失败)。commit 成功后置 null,避免 finally 路径误 abandon。
        var session: PackageInstaller.Session? = null
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            // APK 复制到 PackageInstaller session 是几十 MB 到数百 MB 的磁盘 IO，
            // 必须在后台线程执行，否则主线程卡顿数秒（老盒子上直接 ANR）。
            withContext(Dispatchers.IO) {
                FileInputStream(apk).use { input ->
                    val size = apk.length()
                    val out = session.openWrite("base.apk", 0, size)
                    input.copyTo(out)
                    session.fsync(out)
                    out.close()
                }
            }

            val intent = Intent(installAction).apply {
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            registerInstallReceiver()
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null
            InstallLaunchResult.Started
        } catch (e: CancellationException) {
            runCatching { session?.abandon() }
            throw e
        } catch (error: Throwable) {
            runCatching { session?.abandon() }
            AppLog.w("Update", "PackageInstaller session failed: ${error.message}", error)
            InstallLaunchResult.Failed("安装失败：${error.message}")
        }
    }

    /**
     * 注册安装结果广播,并安排超时兜底注销:用户在系统安装器里取消安装、或
     * 系统未回传结果时,receiver 原本会一直挂到进程结束;超时后自行注销。
     * 再次安装时旧 receiver 先注销、旧超时回调先移除。
     */
    private fun registerInstallReceiver() {
        unregisterInstallReceiver(installReceiver)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("InlinedApi")
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                @Suppress("InlinedApi")
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.i("Update", "install result: status=$status message=${statusMessage?.take(300)}")
                // STATUS_PENDING_USER_ACTION = 系统需要用户确认(应用没有静默安装
                // 权限时必然出现):EXTRA_INTENT 里携带确认对话框。**不能在这里直接
                // startActivity**:receiver 属后台上下文,Android 9+ 的后台 Activity
                // 启动限制会静默拦截(模拟器实测:无异常、无 ATM START 记录、对话框
                // 不出现)。把 Intent 交给 onPendingUserAction 回调,由前台 Activity
                // 启动;保留 receiver 等用户确认后的最终结果广播。
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        onPendingUserAction(confirm)
                        return
                    }
                    AppLog.w("Update", "pending user action but no confirmation intent")
                }
                unregisterInstallReceiver(this)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    AppLog.i("Update", "install succeeded")
                    pendingApk = null
                } else {
                    handleInstallFailure(status, statusMessage)
                }
            }
        }
        installReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(installAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val timeout = Runnable {
            unregisterInstallReceiver(receiver)
            AppLog.w("Update", "install result timeout, receiver unregistered")
        }
        receiverTimeout = timeout
        mainHandler.postDelayed(timeout, TimeUnit.MINUTES.toMillis(INSTALL_RESULT_TIMEOUT_MINUTES))
    }

    /** 注销安装结果广播并清掉超时兜底(幂等)。 */
    private fun unregisterInstallReceiver(receiver: BroadcastReceiver?) {
        receiverTimeout?.let { mainHandler.removeCallbacks(it) }
        receiverTimeout = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        if (receiver === installReceiver) installReceiver = null
    }

    /** PackageInstaller session 被系统拒绝后的收尾:能兼容回退就交前台 Activity 启动系统安装器,否则上报失败原因。 */
    private fun handleInstallFailure(status: Int, statusMessage: String?) {
        val reason = installFailureMessage(status, statusMessage)
        AppLog.w("Update", "install failed: $reason")
        val apk = pendingApk
        // 兼容回退:部分国产 TV/系统策略会直接拒绝 PackageInstaller session。
        // 回退 Intent 必须由前台 Activity 启动(receiver 直接 startActivity 会被
        // Android 9+ 的 BAL 限制静默拦截)。未允许安装未知应用时不回退——那样只会
        // 再失败一次,直接给出可操作提示。
        val fallbackIntent = if (apk != null && !fallbackAttempted && canInstallPackages()) {
            buildInstallPackageIntent(apk)
        } else {
            null
        }
        pendingApk = null
        if (fallbackIntent != null) {
            fallbackAttempted = true
            AppLog.i("Update", "session install rejected, falling back to ACTION_INSTALL_PACKAGE")
            onPendingUserAction(fallbackIntent)
            return
        }
        val message = if (apk != null && !canInstallPackages()) {
            "安装失败：请先允许安装未知应用，然后重试"
        } else {
            reason
        }
        onInstallFailed(message)
    }

    /**
     * 用 Intent.ACTION_INSTALL_PACKAGE 启动安装器。部分国产 TV 不支持
     * PackageInstaller API 但支持此 intent（比 ACTION_VIEW 兼容性更广）。
     */
    private fun tryInstallWithInstallPackage(apk: File): InstallLaunchResult {
        val intent = buildInstallPackageIntent(apk)
            ?: return InstallLaunchResult.Failed("无法打开系统安装器，请手动安装更新包")
        return try {
            context.startActivity(intent)
            InstallLaunchResult.Started
        } catch (error: Throwable) {
            AppLog.w("Update", "ACTION_INSTALL_PACKAGE failed: ${error.message}", error)
            InstallLaunchResult.Failed("安装失败：${error.message}")
        }
    }

    /** 构造 ACTION_INSTALL_PACKAGE 安装 Intent(首选与 session 失败回退共用);失败返回 null。 */
    private fun buildInstallPackageIntent(apk: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        @Suppress("DEPRECATION")
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.onFailure { error ->
        AppLog.w("Update", "build ACTION_INSTALL_PACKAGE intent failed: ${error.message}", error)
    }.getOrNull()

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

    private companion object {
        // 安装结果广播的兜底注销时限:正常安装(用户确认)秒级回传;超时即视为
        // 结果不再回来(用户取消/系统吞掉),注销 receiver 防止挂到进程结束。
        const val INSTALL_RESULT_TIMEOUT_MINUTES = 10L
    }
}

/**
 * 把 PackageInstaller 安装失败状态转成用户可读原因(附带系统原文,便于用户反馈/日志定位)。
 * 纯函数,JVM 单测覆盖;系统原文截断到 120 字符避免弹窗被撑爆。
 */
@Suppress("InlinedApi")
internal fun installFailureMessage(status: Int, systemMessage: String?): String {
    val reason = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统拦截"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "安装被取消"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装包与已安装版本冲突"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与当前系统不兼容"
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "安装超时"
        else -> "安装失败"
    }
    val trimmed = systemMessage?.trim().orEmpty()
    val detail = if (trimmed.isEmpty()) {
        "（status=$status）"
    } else {
        val raw = if (trimmed.length > 120) trimmed.take(120) + "…" else trimmed
        "（$raw）"
    }
    return reason + detail
}
