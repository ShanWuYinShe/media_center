package com.example.mediacenter.data

object VideoCache {
    @Volatile
    private var _video: VideoSummary? = null

    fun set(video: VideoSummary) {
        _video = video
    }

    fun get(id: String): VideoSummary? {
        val v = _video
        return if (v?.id == id) v else null
    }

    fun clear() {
        _video = null
    }
}
