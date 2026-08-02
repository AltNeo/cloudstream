package com.lagradost.cloudstream3.remote

/**
 * Pure formatting helpers for the companion (phone ⇄ TV) UI surfaces.
 * Kept free of Android dependencies so they stay JVM-unit-testable
 * (see CompanionUiTest in app/src/test/.../remote/).
 */

/** "mm:ss" (or "h:mm:ss" past the hour). Negative values clamp to zero. */
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Compact per-plugin TV sync badge symbol from the last [ExtensionSyncReply] (plan §8.5). */
fun PluginSyncResult.Status.tvSyncBadge(): String = when (this) {
    PluginSyncResult.Status.OK_INSTALLED,
    PluginSyncResult.Status.OK_ALREADY,
    PluginSyncResult.Status.UPDATED -> "✓"
    PluginSyncResult.Status.NEWER_KEPT,
    PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE -> "↻"
    PluginSyncResult.Status.REMOVED,
    PluginSyncResult.Status.FAILED -> "✕"
}
