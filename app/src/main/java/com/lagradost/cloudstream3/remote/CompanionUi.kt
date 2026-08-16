package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.CurrentTracks
import com.lagradost.cloudstream3.ui.player.VideoTrack

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
    PluginSyncResult.Status.UNKNOWN -> ""
}

/**
 * Phone-side display gate for the companion now-playing surfaces (plan §6.5): UNKNOWN is the
 * lenient decode fallback for a state this phone does not know, so it must count as inactive
 * — no active controls or notification may appear for a state we cannot interpret.
 */
val NowPlayingPayload.State.isActive: Boolean
    get() = this != NowPlayingPayload.State.IDLE &&
        this != NowPlayingPayload.State.ENDED &&
        this != NowPlayingPayload.State.UNKNOWN

/**
 * Monotonic phone-side projection of a now-playing payload at [atMs], given it was sampled at
 * [sampledAtMs]. While PLAYING the position advances at the reported [NowPlayingPayload.speed]
 * and is clamped to never drop below the sampled position nor exceed the duration, so between
 * the TV's throttled (10 s) state events the seekbar only moves forward (plan F3 / checkpoint 2).
 * Any other state (PAUSED, BUFFERING, ENDED, IDLE, UNKNOWN) returns the sampled position
 * unchanged. Purely local — it never touches the network.
 */
// ---------------------------------------------------------------------------
// F4a: renderer tracks -> wire payload (video/audio only; text tracks never by id)
// ---------------------------------------------------------------------------

/**
 * Maps the active [IPlayer]'s [CurrentTracks] to the wire [TracksPayload]. Only tracks that
 * carry a renderer [Track.id] are broadcast - a track without an id cannot be selected back
 * by wire id, so it is omitted rather than claimed with a placeholder. Text tracks are never
 * serialized as a list; only the aggregate [TracksPayload.subtitlesEnabled]/[hasTextTracks]
 * flags travel so the phone can offer a subtitle *disable* affordance (F4a).
 */
fun tracksPayloadFrom(tracks: CurrentTracks): TracksPayload = TracksPayload(
    videoTracks = tracks.allVideoTracks.mapNotNull { track ->
        track.id?.let { id ->
            TrackInfo(
                id = id,
                label = videoTrackLabel(track),
                language = track.language,
                mimeType = track.sampleMimeType,
                width = track.width,
                height = track.height,
                extra = videoTrackExtra(track.width, track.height),
            )
        }
    },
    audioTracks = tracks.allAudioTracks.mapNotNull { track ->
        track.id?.let { id ->
            TrackInfo(
                id = id,
                label = audioTrackLabel(track),
                language = track.language,
                mimeType = track.sampleMimeType,
                formatIndex = track.formatIndex,
                extra = audioTrackExtra(track.channelCount, track.sampleMimeType),
            )
        }
    },
    currentVideoId = tracks.currentVideoTrack?.id,
    currentAudioId = tracks.currentAudioTrack?.id,
    subtitlesEnabled = tracks.currentTextTracks.isNotEmpty(),
    hasTextTracks = tracks.allTextTracks.isNotEmpty(),
)

private fun videoTrackLabel(track: VideoTrack): String =
    track.label?.takeIf { it.isNotBlank() }
        ?: videoTrackExtra(track.width, track.height)
        ?: "Video"

private fun videoTrackExtra(width: Int?, height: Int?): String? =
    if (width != null && height != null && width > 0 && height > 0) "${width}x${height}" else null

private fun audioTrackLabel(track: AudioTrack): String =
    track.language?.trim()?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercaseChar() }
        ?: track.label?.takeIf { it.isNotBlank() }
        ?: "Audio"

private fun audioTrackExtra(channelCount: Int?, sampleMimeType: String?): String? {
    val channels = when {
        channelCount == null || channelCount <= 0 -> null
        channelCount == 1 -> "Mono"
        channelCount == 2 -> "Stereo"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "${channelCount}ch"
    }
    val codec = sampleMimeType?.substringAfter('/')?.uppercase()?.takeIf { it.isNotBlank() }
    return listOfNotNull(channels, codec).joinToString(" \u2022 ").ifBlank { null }
}

fun interpolatedPositionMs(payload: NowPlayingPayload, sampledAtMs: Long, atMs: Long): Long {
    val duration = payload.durationMs
    if (payload.state != NowPlayingPayload.State.PLAYING) {
        // Non-PLAYING: no interpolation; clamp only when a sane duration is known.
        return if (duration > 0L) payload.positionMs.coerceIn(0L, duration) else payload.positionMs
    }
    // PLAYING without a known duration cannot be projected: stay at the sampled position.
    if (duration <= 0L) return payload.positionMs
    val elapsedMs = (atMs - sampledAtMs).coerceAtLeast(0L)
    val speed = payload.speed.takeIf { it > 0f } ?: 1f
    return (payload.positionMs + (elapsedMs * speed).toLong())
        .coerceIn(payload.positionMs, duration)
}
