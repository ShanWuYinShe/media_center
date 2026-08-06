package com.example.mediacenter.log

import android.content.Context
import android.util.Log
import com.example.mediacenter.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 统一的应用日志入口:在系统 logcat 之外,额外把日志落到 [filesDir]/logs 下的滚动文件,
 * 让进程被杀 / App 重启后仍能取回崩溃前的轨迹(典型:播放异常导致进程死亡掉回首页,
 * logcat 已被冲掉)。
 *
 * 合规:服务端 thumbnail / playback URL 是带 sig 的签名代理地址,**不得落盘**。
 * 因此所有写入(含 logcat 与文件)都会先经 [redact] 抹掉 sig / token / url 等敏感片段,
 * 作为兜底;调用方记录地址时也应优先用 [redactUrl] 只保留 host+path。
 */
object AppLog {
    private const val TAG_PREFIX = "MediaCenter"
    private const val MAX_FILE_BYTES = 512L * 1024
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // 敏感查询参数:值一律抹成 ***。覆盖签名代理地址(sig/url)与可能出现的鉴权片段。
    private val sensitiveParam =
        Regex("(?i)([?&](?:sig|signature|token|key|url|expires|exp|secret)=)[^&\\s\"]*")

    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLog-file").apply { isDaemon = true }
    }
    private val fileLock = Any()

    @Volatile private var logFile: File? = null
    @Volatile private var rotatedFile: File? = null
    // 单线程 executor 串行写入:缓存当前文件长度,避免每条日志都 stat 一次。
    private var cachedLength = 0L

    /** 在 Application 启动时调用一次,准备文件落盘目录。失败不影响 logcat 输出。 */
    fun init(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "app.log")
            rotatedFile = File(dir, "app.log.1")
            i("AppLog", "logging to ${dir.absolutePath} (debug=${BuildConfig.DEBUG})")
        }
    }

    /** 仅 DEBUG 构建输出的详细日志,避免发布包刷屏 / 写盘。 */
    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val safe = redact(message)
        Log.d(fullTag(tag), safe)
        appendToFile("D", tag, safe, null)
    }

    fun i(tag: String, message: String) {
        val safe = redact(message)
        Log.i(fullTag(tag), safe)
        appendToFile("I", tag, safe, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        val safe = redact(message)
        if (error != null) Log.w(fullTag(tag), safe, error) else Log.w(fullTag(tag), safe)
        appendToFile("W", tag, safe, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val safe = redact(message)
        if (error != null) Log.e(fullTag(tag), safe, error) else Log.e(fullTag(tag), safe)
        appendToFile("E", tag, safe, error)
    }

    /**
     * 致命错误(未捕获异常)同步写盘:进程即将退出,不能依赖后台线程,否则文件还没落就被杀。
     */
    fun logFatalBlocking(tag: String, message: String, error: Throwable) {
        val safe = redact(message)
        Log.e(fullTag(tag), safe, error)
        writeLine(format("E", tag, safe, error))
    }

    /** 抹掉签名地址里的 sig/token/url 等敏感片段,任意字符串可用。 */
    fun redact(text: String): String = sensitiveParam.replace(text) { "${it.groupValues[1]}***" }

    /** 记录地址时优先使用:只保留 scheme://host/path,丢弃整个查询串。 */
    fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<none>"
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        return if (q >= 0) "$base?…" else base
    }

    private fun fullTag(tag: String): String = "$TAG_PREFIX.$tag"

    private fun appendToFile(level: String, tag: String, message: String, error: Throwable?) {
        if (logFile == null) return
        val line = format(level, tag, message, error)
        runCatching { fileExecutor.execute { writeLine(line) } }
    }

    private fun format(level: String, tag: String, message: String, error: Throwable?): String {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val base = "$time $level/$tag: $message"
        return if (error == null) base else "$base\n${redact(Log.getStackTraceString(error))}"
    }

    private fun writeLine(line: String) {
        val target = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                if (cachedLength == 0L) {
                    cachedLength = target.length()
                }
                if (cachedLength > MAX_FILE_BYTES) {
                    rotatedFile?.let { rotated ->
                        rotated.delete()
                        target.renameTo(rotated)
                    }
                    cachedLength = 0L
                }
                val payload = "$line\n"
                target.appendText(payload)
                cachedLength += payload.length
            }
        }
    }

    /** 清除全部日志文件(设置页"清除数据")。 */
    fun clear() {
        runCatching {
            fileExecutor.execute {
                synchronized(fileLock) {
                    logFile?.delete()
                    rotatedFile?.delete()
                    cachedLength = 0L
                }
            }
        }
    }

    /** 读取日志尾部若干行(设置页查看日志,便于远程诊断崩溃)。 */
    fun tail(maxLines: Int = 200): String {
        val target = logFile ?: return ""
        return runCatching {
            target.readLines().takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }
}
