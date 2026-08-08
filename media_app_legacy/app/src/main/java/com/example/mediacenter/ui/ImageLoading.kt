package com.example.mediacenter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import com.example.mediacenter.MainActivity
import androidx.lifecycle.lifecycleScope
import com.example.mediacenter.ui.config.BackdropAlpha
import com.example.mediacenter.ui.config.MaxThumbnailBytes
import com.example.mediacenter.ui.config.MaxThumbnailRetries
import com.example.mediacenter.ui.config.PlaybackUserAgent
import com.example.mediacenter.ui.config.ThumbnailRetryBackoffMs
import com.example.mediacenter.ui.config.containsHeader
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.views.ThumbnailImageView
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

internal fun MainActivity.loadImage(
        url: String?,
        headers: Map<String, String>,
        image: ThumbnailImageView,
        placeholder: View,
        title: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ) {
        val imageUrl = url?.takeIf { it.isNotBlank() }
        image.cancelThumbnailLoad()
        image.tag = imageUrl
        image.setThumbnailBitmap(null)
        setPlaceholderVisible(placeholder, true)
        if (imageUrl == null) return
        ImageMemoryCache.get(imageUrl)?.let {
            image.setThumbnailBitmap(it)
            setPlaceholderVisible(placeholder, false)
            return
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(@Suppress("UNUSED_PARAMETER") view: View) = Unit

            override fun onViewDetachedFromWindow(@Suppress("UNUSED_PARAMETER") view: View) {
                image.cancelThumbnailLoad()
            }
        }
        val job = lifecycleScope.launch {
            try {
                val bitmap = ThumbnailFetchSemaphore.withPermit {
                    fetchBitmap(
                        url = imageUrl,
                        headers = headers,
                        maxWidthPx = maxWidthPx.coerceAtLeast(dp(160)),
                        maxHeightPx = maxHeightPx.coerceAtLeast(dp(120)),
                    )
                }
                if (bitmap != null) {
                    ImageMemoryCache.put(imageUrl, bitmap)
                } else {
                    Log.w("MediaCenterImage", "Unable to load thumbnail for $title")
                }
                if (image.tag == imageUrl && bitmap != null) {
                    image.setThumbnailBitmap(bitmap)
                    setPlaceholderVisible(placeholder, false)
                }
            } finally {
                image.clearThumbnailLoad(job = coroutineContext[Job])
            }
        }
        image.trackThumbnailLoad(job, detachListener)
    }

    // 详情页模糊背景:复用缩略图字节,降采样到极小尺寸后由 ImageView 双线性放大近似模糊
    // (minSdk=19,无法用 RenderEffect)。背景位图单独按 "backdrop:" key 缓存,只在内存渲染。
internal fun MainActivity.loadBackdropImage(
        url: String?,
        headers: Map<String, String>,
        target: ThumbnailImageView,
    ) {
        val imageUrl = url?.takeIf { it.isNotBlank() } ?: return
        val cacheKey = "backdrop:$imageUrl"
        target.tag = cacheKey
        ImageMemoryCache.get(cacheKey)?.let {
            target.setThumbnailBitmap(it)
            target.animate().alpha(BackdropAlpha).setDuration(420).start()
            return
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(@Suppress("UNUSED_PARAMETER") view: View) = Unit

            override fun onViewDetachedFromWindow(@Suppress("UNUSED_PARAMETER") view: View) {
                target.cancelThumbnailLoad()
            }
        }
        val job = lifecycleScope.launch {
            try {
                val bitmap = ThumbnailFetchSemaphore.withPermit {
                    val bytes = fetchThumbnailBytesWithRetry(imageUrl, headers)
                    // 解码放 IO 池:BitmapFactory 是 native 活,不占 Default 池——
                    // 低核老盒子上 Default 与 MediaApi 的 JSON 解码共享,图片解码
                    // 会饿死网络响应解析,表现为列表滚动时请求超时。
                    withContext(Dispatchers.IO) {
                        // 固定极小目标像素(不随密度),放大后形成柔和模糊。
                        decodeSampledBitmap(bytes, 48, 48)
                    }
                }
                if (bitmap != null) {
                    ImageMemoryCache.put(cacheKey, bitmap)
                    if (target.tag == cacheKey) {
                        target.setThumbnailBitmap(bitmap)
                        target.animate().alpha(BackdropAlpha).setDuration(420).start()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                Log.w("MediaCenterImage", "Backdrop request failed", error)
            } catch (error: RuntimeException) {
                Log.w("MediaCenterImage", "Backdrop request failed", error)
            } finally {
                target.clearThumbnailLoad(job = coroutineContext[Job])
            }
        }
        target.trackThumbnailLoad(job, detachListener)
    }

internal fun setPlaceholderVisible(placeholder: View, visible: Boolean) {
        placeholder.animate().cancel()
        placeholder.visibility = View.VISIBLE
        placeholder.alpha = if (visible) 1f else 0f
    }

internal suspend fun MainActivity.fetchBitmap(
        url: String,
        headers: Map<String, String>,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap? {
        return try {
            val bytes = fetchThumbnailBytesWithRetry(url, headers)
            // 解码放 IO 池:BitmapFactory 是 native 活,不占 Default 池——
            // 低核老盒子上 Default 与 MediaApi 的 JSON 解码共享,图片解码
            // 会饿死网络响应解析,表现为列表滚动时请求超时。
            withContext(Dispatchers.IO) {
                decodeSampledBitmap(bytes, maxWidthPx, maxHeightPx)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.w("MediaCenterImage", "Thumbnail request failed", error)
            null
        } catch (error: RuntimeException) {
            Log.w("MediaCenterImage", "Thumbnail request failed", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.e("MediaCenterImage", "Thumbnail decode exceeded memory budget", error)
            null
        }
    }

    // asset 代理回源偶发瞬时 5xx(典型为 Cloudflare error code: 502),对瞬时失败做
    // 有限指数退避重试,缓存命中后通常即可恢复,避免直接降级为占位图。
internal suspend fun MainActivity.fetchThumbnailBytesWithRetry(
        url: String,
        headers: Map<String, String>,
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return fetchThumbnailBytes(url, headers)
            } catch (error: ThumbnailHttpException) {
                if (!error.isTransient || attempt >= MaxThumbnailRetries) throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= MaxThumbnailRetries) throw error
            }
            delay(ThumbnailRetryBackoffMs[attempt])
            attempt++
        }
    }

internal suspend fun MainActivity.fetchThumbnailBytes(
        url: String,
        headers: Map<String, String>,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        if (!headers.containsHeader("Accept")) {
            requestBuilder.header("Accept", "image/*,*/*;q=0.8")
        }
        if (!headers.containsHeader("User-Agent")) {
            requestBuilder.header("User-Agent", PlaybackUserAgent)
        }
        headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()
        val call = ThumbnailHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.readBoundedThumbnailBytes()
                    if (continuation.isActive) {
                        continuation.resume(bytes)
                    }
                } catch (error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                } catch (error: RuntimeException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

internal fun Response.readBoundedThumbnailBytes(): ByteArray {
        return use { response ->
            if (!response.isSuccessful) {
                throw ThumbnailHttpException(response.code())
            }
            val body = response.body() ?: throw IOException("Thumbnail response body is empty")
            val contentLength = body.contentLength()
            if (contentLength > MaxThumbnailBytes) {
                throw IOException("Thumbnail exceeds $MaxThumbnailBytes bytes")
            }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalBytes = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > MaxThumbnailBytes) {
                        throw IOException("Thumbnail exceeds $MaxThumbnailBytes bytes")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

internal fun decodeSampledBitmap(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

internal fun bitmapSampleSize(width: Int, height: Int, maxWidthPx: Int, maxHeightPx: Int): Int {
        var sampleSize = 1
        val targetWidth = maxWidthPx.coerceAtLeast(1)
        val targetHeight = maxHeightPx.coerceAtLeast(1)
        while (height / (sampleSize * 2) >= targetHeight && width / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }
