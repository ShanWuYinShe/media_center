package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStorageKeyTest {

    @Test
    fun single_page_uses_plain_video_id() {
        assertEquals("BV1xx", playbackStorageKey("BV1xx", page = 0))
    }

    @Test
    fun multi_page_uses_page_suffix() {
        // 第 2 页(0 起始 page=1)→ #p2,与 UI 的"N 页"表述一致
        assertEquals("BV1xx#p2", playbackStorageKey("BV1xx", page = 1))
        assertEquals("BV1xx#p3", playbackStorageKey("BV1xx", page = 2))
    }

    @Test
    fun youtube_id_supported() {
        assertEquals("jNQXAC9IVRw", playbackStorageKey("jNQXAC9IVRw", page = 0))
        assertEquals("jNQXAC9IVRw#p2", playbackStorageKey("jNQXAC9IVRw", page = 1))
    }

    @Test
    fun negative_page_falls_back_to_plain_id() {
        assertEquals("BV1xx", playbackStorageKey("BV1xx", page = -1))
    }

    @Test
    fun ids_with_hash_remain_distinguishable() {
        // key 加 #pN 后缀,不同页互不相同;与不含 # 的原 id 也不冲突
        assertEquals("BV1xx#p2", playbackStorageKey("BV1xx", page = 1))
        assertEquals("BV1xx#p3", playbackStorageKey("BV1xx", page = 2))
    }
}
