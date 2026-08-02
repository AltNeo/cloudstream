package com.lagradost.cloudstream3.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionUiTest {

    // ------------------------------------------------------------------
    // formatPlaybackTime
    // ------------------------------------------------------------------

    @Test
    fun `formatPlaybackTime sub-minute is mm ss`() {
        assertEquals("00:42", formatPlaybackTime(42_000))
    }

    @Test
    fun `formatPlaybackTime over an hour is h mm ss`() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }

    @Test
    fun `formatPlaybackTime clamps negatives to zero`() {
        assertEquals("00:00", formatPlaybackTime(-5))
    }

    @Test
    fun `formatPlaybackTime zero is 00 00`() {
        assertEquals("00:00", formatPlaybackTime(0))
    }

    // ------------------------------------------------------------------
    // PluginSyncResult.Status.tvSyncBadge
    // ------------------------------------------------------------------

    @Test
    fun `successful sync statuses show a checkmark`() {
        assertEquals("✓", PluginSyncResult.Status.OK_INSTALLED.tvSyncBadge())
        assertEquals("✓", PluginSyncResult.Status.OK_ALREADY.tvSyncBadge())
        assertEquals("✓", PluginSyncResult.Status.UPDATED.tvSyncBadge())
    }

    @Test
    fun `pending sync statuses show a refresh symbol`() {
        assertEquals("↻", PluginSyncResult.Status.NEWER_KEPT.tvSyncBadge())
        assertEquals("↻", PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE.tvSyncBadge())
    }

    @Test
    fun `failed and removed statuses show a cross`() {
        assertEquals("✕", PluginSyncResult.Status.FAILED.tvSyncBadge())
        assertEquals("✕", PluginSyncResult.Status.REMOVED.tvSyncBadge())
    }
}
