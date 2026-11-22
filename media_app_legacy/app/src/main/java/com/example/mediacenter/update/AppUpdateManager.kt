package com.example.mediacenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import android.os.Build
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateManager(
    private val context: Context,
    private val api: MediaApi,
    private val client: OkHttpClient = AppHttpClient.shared,
    // PackageInstaller 需要用户确认时的确认 Intent 回调:必须由前台 Activity 启动
    // (Android 9+ 的后台 Activity 启动限制会静默拦截 receiver 里的 startActivity,
    // 模拟器实测确认框不出现)。默认空实现保证既有调用方兼容。
    private val onPendingConfirm: (android.content.Intent) -> Unit = {},
    // 安装最终失败的用户可读原因回调。PackageInstaller session 被系统拒绝时,
    // 先尝试一次 ACTION_INSTALL_PACKAGE 兼容回退(由前台 Activity 启动);
    // 回退不可用(如未允许安装未知应用)或没有兜底路径时才回调这里,
    // 由 MainActivity 弹「更新失败」提示,不再静默停在旧版本。
    private val onInstallFailed: (String) -> Unit = {},
) {
    private var installReceiver: BroadcastReceiver? = null
    // 安装结果广播的兜底注销时限:用户取消安装/系统吞掉结果时,receiver 不能
    // 一直挂到进程结束;超时后自行注销(再次安装时先注销旧 receiver)。
    private var receiverTimeout: Runnable? = null
    // BroadcastReceiver 注销本身线程安全,排期统一走主线程 Handler。
    private val mainHandler = Handler(Looper.getMainLooper())
    // 本次安装的 APK:session 失败时拿它发起兼容回退,随 commit 一起记住。
    private var pendingApk: File? = null
    // 回退只尝试一次:失败结果会再次进 receiver,避免无限递归。
    private var fallbackAttempted = false
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
        // ACTION_MANAGE_UNKNOWN_APP_SOURCES 是 API 26 引入的常量,直接引用会在
        // minSdk 19 上触发 lint InlinedApi。用字符串字面量 + runCatching 兜底:
        // API 26+ 解析成功跳到安装权限页;< 26 或国产 TV 不支持则降级到应用详情页。
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
    private suspend fun tryInstallWithSession(apk: File): InstallLaunchResult {
        // session 兜底:复制 APK 抛异常/协程取消时 abandon 掉会话——失败的会话残留
        // 在系统 PackageInstaller 里,老盒子会话配额有限,累积会导致后续更新失败。
        // commit 成功后置 null,避免异常路径误 abandon 已提交的会话。
        var session: PackageInstaller.Session? = null
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            // APK 复制到 PackageInstaller session 是磁盘 IO，必须在后台线程执行，
            // 否则老盒子上主线程卡顿数秒甚至 ANR。
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
            // FLAG_IMMUTABLE 是 API 23+ 才有的常量;在 API 21/22 上引用会 NoSuchFieldError。
            // 本方法入口守卫是 >= LOLLIPOP(API 21),因此必须按版本选择标志。
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent, pendingFlags,
            )

            registerInstallReceiver()
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null
            InstallLaunchResult.Started
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { session?.abandon() }
            throw e
        } catch (error: Throwable) {
            runCatching { session?.abandon() }
            AppLog.w("Update", "PackageInstaller session failed: ${error.message}", error)
            InstallLaunchResult.Failed("安装失败：${error.message}")
        }
    }

    /**
     * 注册安装结果广播:成功/失败统一处理,失败时先尝试兼容回退。
     * 安装结果可能很晚才回来(等用户确认),超时兜底注销 receiver。
     */
    private fun registerInstallReceiver() {
        unregisterInstallReceiver(installReceiver)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                @Suppress("DEPRECATION")
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.i("Update", "install result: status=$status message=${statusMessage?.take(300)}")
                // STATUS_PENDING_USER_ACTION = 系统需要用户确认:EXTRA_INTENT 里
                // 携带确认对话框,**必须由前台 Activity 启动**(receiver 属后台
                // 上下文,BAL 限制会静默拦截 startActivity,模拟器实测确认框
                // 不出现)。转发给回调,由 MainActivity 启动;保留 receiver 等
                // 用户确认后的最终结果广播。
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        onPendingConfirm(confirm)
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
        // ContextCompat 统一处理 Android 13+ 的 RECEIVER_NOT_EXPORTED 要求(兼容 minSdk 19)。
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

    /** PackageInstaller session 被拒后的收尾:能兼容回退就交前台 Activity 启动系统安装器,否则上报失败原因。 */
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
            onPendingConfirm(fallbackIntent)
            return
        }
        val message = if (apk != null && !canInstallPackages()) {
            "更新失败：请先允许安装未知应用，然后重试"
        } else {
            "更新失败：$reason"
        }
        onInstallFailed(message)
    }

    /** 构造 ACTION_INSTALL_PACKAGE 安装 Intent(首选与 session 失败回退共用);失败返回 null。 */
    private fun buildInstallPackageIntent(apk: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        // Intent.ACTION_INSTALL_PACKAGE 字段在 API 26+ 被标记 deprecated(推荐
        // PackageInstaller),但国产 TV 常不支持 PackageInstaller API,此 intent
        // 是必要的兜底。用字符串字面量替代 deprecated 字段引用,消除编译警告。
        Intent("android.intent.action.INSTALL_PACKAGE").apply {
            data = uri
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.onFailure { error ->
        AppLog.w("Update", "build ACTION_INSTALL_PACKAGE intent failed: ${error.message}", error)
    }.getOrNull()

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
// 安装结果广播的兜底注销时限:正常安装(用户确认)秒级回传;超时即视为
// 结果不再回来(用户取消/系统吞掉),注销 receiver 防止挂到进程结束。
private const val INSTALL_RESULT_TIMEOUT_MINUTES = 10L
private val CHECK_RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L)

sealed class InstallLaunchResult {
    object Started : InstallLaunchResult()
    object NeedsPermission : InstallLaunchResult()
    data class Failed(val message: String) : InstallLaunchResult()
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
