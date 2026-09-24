package com.example.mediacenter.crash

import android.content.Context
import android.os.Build
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.log.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 崩溃上报:
 * - [write] 在未捕获异常时把崩溃信息同步落盘(进程即将退出,不能依赖后台线程),
 *   与 AppLog.logFatalBlocking 的滚动日志互补——这里是结构化单文件,便于上传。
 * - [uploadPending] 在下次启动时把待上报的崩溃文件 POST 到
 *   `/api/crash/report`,成功后删除;失败保留文件,下次启动再试。
 * 只传一次、不重试,避免启动时阻塞网络。
 */
object CrashReporter {

    private const val FILE_NAME = "crash_report.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CrashReportPayload(
        val packageName: String,
        val appVersionName: String,
        val appVersionCode: Int,
        val osVersion: String,
        val device: String,
        val occurredAt: String,
        val stackTrace: String,
    )

    private fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun write(context: Context, throwable: Throwable) {
        val occurredAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .format(Date())
        val payload = CrashReportPayload(
            packageName = BuildConfig.APPLICATION_ID,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            occurredAt = occurredAt,
            stackTrace = AppLog.redact(android.util.Log.getStackTraceString(throwable)),
        )
        // 用 kotlinx.serialization 序列化,完整转义控制字符——崩溃堆栈输入不可控,
        // 手写转义漏掉 \b/\f/其它 0x00-0x1F 会生成非法 JSON,导致服务端 400、上报永远重试。
        runCatching {
            crashFile(context).writeText(json.encodeToString(payload))
        }
    }

    suspend fun uploadPending(context: Context) {
        val file = crashFile(context)
        if (!file.exists()) return
        val body = runCatching { file.readText() }.getOrNull() ?: return
        try {
            // 共享的全局轻量客户端(holder 在 Application.onCreate 先于本调用初始化)
            val ok = MediaRepositoryHolder.updateApi.reportCrash(body)
            if (ok) {
                runCatching { file.delete() }
                AppLog.i("Crash", "crash report uploaded")
            } else {
                AppLog.w("Crash", "crash report upload rejected, kept for next launch")
            }
        } catch (e: Exception) {
            AppLog.w("Crash", "crash report upload failed, kept for next launch: ${e.message}")
        }
    }
}
