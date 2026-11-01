package com.example.mediacenter.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * 把 OkHttp 的 enqueue/callback 收敛为可取消的挂起调用。
 *
 * 项目里有 4 处各自手写 `suspendCancellableCoroutine + Callback`（目录 API、
 * YouTube 代理、B 站 API、APK 下载），细节容易漂移（漏判 isActive、取消时
 * 不 close body、返回值未释放等）。这里只保留一份实现：
 * - 协程取消 → cancel 正在进行的请求；
 * - 响应到达时协程已取消 → 立即 close，避免连接/响应体泄漏；
 * - 调用方必须 `use { }` 消费返回的 [Response]。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}
