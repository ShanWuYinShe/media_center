package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 只缓存公开视频详情的稳定元数据。
 *
 * 服务端返回的 thumbnailUrl / playbackOptions[].url 可能是短期有效的代理 URL,也可能是
 * 仅当前会话可用的直连媒体地址;都不持久化。播放前必须通过 /videos/{id}/playback 刷新
 * 最新播放地址。上游请求头同样不落盘,缓存回放时按空占位恢复。
 */
class VideoDetailsCache(
    cacheDir: File,
    cacheDirectoryName: String = CategoryCacheDirectoryName,
    private val maxBytes: Long = 50L * 1024L * 1024L,
    private val maxAgeMillis: Long = 24L * 60L * 60L * 1000L,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val root = File(cacheDir, cacheDirectoryName)

    suspend fun get(video: VideoSummary): VideoDetails? = withContext(Dispatchers.IO) {
        val file = fileFor(video.id)
        if (!file.isFile) return@withContext null
        try {
            val cached = json.decodeFromString<CachedVideoDetails>(file.readText(Charsets.UTF_8))
            if (cached.id != video.id) {
                AppLog.w("Cache", "Ignoring mismatched cached details for ${video.id}")
                file.delete()
                null
            } else if (cached.isExpired(maxAgeMillis)) {
                file.delete()
                null
            } else {
                file.setLastModified(System.currentTimeMillis())
                cached.toVideoDetails(video)
            }
        } catch (error: Exception) {
            AppLog.w("Cache", "Unable to read cached details for ${video.id}", error)
            file.delete()
            null
        }
    }

    suspend fun put(details: VideoDetails) = withContext(Dispatchers.IO) {
        try {
            root.mkdirs()
            val file = fileFor(details.id)
            val tmp = File(root, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(CachedVideoDetails.from(details)), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            file.setLastModified(System.currentTimeMillis())
            trimToMaxSize()
        } catch (error: Exception) {
            AppLog.w("Cache", "Unable to cache details for ${details.id}", error)
        }
    }

    private fun trimToMaxSize() {
        val files = root.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }

    private fun fileFor(videoId: String): File {
        return File(root, "${sha256(videoId)}.json")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val CategoryCacheDirectoryName = "video-details"
        const val SearchCacheDirectoryName = "video-details-search"
        const val SearchMaxBytes = 20L * 1024L * 1024L
        const val SearchMaxAgeMillis = 60L * 60L * 1000L
    }
}

@Serializable
private data class CachedVideoDetails(
    val id: String,
    val videoKind: String = "vod",
    val title: String,
    val description: String = "",
    val year: String? = null,
    val creators: List<String> = emptyList(),
    val durationSeconds: Long? = null,
    val playbackKind: String = "vod",
    val playbackOptions: List<CachedPlaybackOption> = emptyList(),
    val cachedAtMillis: Long = 0L,
) {
    fun isExpired(maxAgeMillis: Long): Boolean {
        val age = System.currentTimeMillis() - cachedAtMillis
        return cachedAtMillis <= 0L || age > maxAgeMillis
    }

    fun toVideoDetails(video: VideoSummary): VideoDetails {
        return VideoDetails(
            id = id,
            videoKind = videoKind,
            title = title,
            description = description,
            year = year,
            creators = creators,
            thumbnailUrl = video.thumbnailUrl,
            thumbnailHeaders = video.thumbnailHeaders,
            thumbnailDelivery = video.thumbnailDelivery,
            durationSeconds = durationSeconds,
            playbackKind = playbackKind,
            playbackOptions = playbackOptions.map { it.toPlaybackOption() },
        )
    }

    companion object {
        fun from(details: VideoDetails): CachedVideoDetails {
            return CachedVideoDetails(
                id = details.id,
                videoKind = details.videoKind,
                title = details.title,
                description = details.description,
                year = details.year,
                creators = details.creators,
                durationSeconds = details.durationSeconds,
                playbackKind = details.playbackKind,
                playbackOptions = details.playbackOptions.map(CachedPlaybackOption::from),
                cachedAtMillis = System.currentTimeMillis(),
            )
        }
    }
}

@Serializable
private data class CachedPlaybackOption(
    val id: String,
    val kind: String,
    val label: String,
    val mimeType: String? = null,
    val delivery: String = DeliveryMode.DIRECT,
) {
    fun toPlaybackOption(): PlaybackOption {
        return PlaybackOption(
            id = id,
            kind = kind,
            label = label,
            url = "",
            mimeType = mimeType,
            headers = emptyMap(),
            delivery = delivery,
        )
    }

    companion object {
        fun from(option: PlaybackOption): CachedPlaybackOption {
            return CachedPlaybackOption(
                id = option.id,
                kind = option.kind,
                label = option.label,
                mimeType = option.mimeType,
                delivery = option.delivery,
            )
        }
    }
}
