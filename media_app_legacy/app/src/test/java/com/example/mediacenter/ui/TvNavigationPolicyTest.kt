package com.example.mediacenter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TvNavigationPolicyTest {

    @Test
    fun `evaluateHomeBack refocuses category tab when content or topbar has focus`() {
        val (result, updatedMs) = TvNavigationPolicy.evaluateHomeBack(
            hasContentOrTopBarFocus = true,
            nowMs = 5000L,
            lastBackPressMs = 0L,
        )
        assertEquals(TvNavigationPolicy.HomeBackResult.REFOCUS_CATEGORY_TAB, result)
        assertEquals(0L, updatedMs)
    }

    @Test
    fun `evaluateHomeBack shows toast on first press when on category tab`() {
        val (result, updatedMs) = TvNavigationPolicy.evaluateHomeBack(
            hasContentOrTopBarFocus = false,
            nowMs = 5000L,
            lastBackPressMs = 0L,
        )
        assertEquals(TvNavigationPolicy.HomeBackResult.SHOW_EXIT_TOAST, result)
        assertEquals(5000L, updatedMs)
    }

    @Test
    fun `evaluateHomeBack finishes activity on rapid second press`() {
        val (result, updatedMs) = TvNavigationPolicy.evaluateHomeBack(
            hasContentOrTopBarFocus = false,
            nowMs = 6200L,
            lastBackPressMs = 5000L,
        )
        assertEquals(TvNavigationPolicy.HomeBackResult.FINISH_ACTIVITY, result)
        assertEquals(5000L, updatedMs)
    }

    @Test
    fun `evaluateHomeBack shows toast again if interval exceeds timeout`() {
        val (result, updatedMs) = TvNavigationPolicy.evaluateHomeBack(
            hasContentOrTopBarFocus = false,
            nowMs = 7500L,
            lastBackPressMs = 5000L,
        )
        assertEquals(TvNavigationPolicy.HomeBackResult.SHOW_EXIT_TOAST, result)
        assertEquals(7500L, updatedMs)
    }

    @Test
    fun `evaluatePlayerBack hides controls overlay when overlay is showing and no error`() {
        val decision = TvNavigationPolicy.evaluatePlayerBack(
            controlsVisible = true,
            isErrorShowing = false,
        )
        assertEquals(TvNavigationPolicy.PlayerBackDecision.HIDE_CONTROLS_OVERLAY, decision)
    }

    @Test
    fun `evaluatePlayerBack exits playback when controls are already hidden`() {
        val decision = TvNavigationPolicy.evaluatePlayerBack(
            controlsVisible = false,
            isErrorShowing = false,
        )
        assertEquals(TvNavigationPolicy.PlayerBackDecision.EXIT_PLAYBACK, decision)
    }

    @Test
    fun `evaluatePlayerBack exits playback immediately on fatal error even if controls flag was true`() {
        val decision = TvNavigationPolicy.evaluatePlayerBack(
            controlsVisible = true,
            isErrorShowing = true,
        )
        assertEquals(TvNavigationPolicy.PlayerBackDecision.EXIT_PLAYBACK, decision)
    }
}
