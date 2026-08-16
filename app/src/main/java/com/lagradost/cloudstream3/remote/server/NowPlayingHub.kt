package com.lagradost.cloudstream3.remote.server

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context as appContext
import com.lagradost.cloudstream3.remote.DeviceInfo
import com.lagradost.cloudstream3.remote.InputContextPayload
import com.lagradost.cloudstream3.remote.LanRemoteProtocol
import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.PlaybackChoice
import com.lagradost.cloudstream3.remote.RemoteEvent
import com.lagradost.cloudstream3.remote.SelectPlaybackOptionPayload
import com.lagradost.cloudstream3.remote.SelectTrackPayload
import com.lagradost.cloudstream3.remote.TracksPayload
import com.lagradost.cloudstream3.remote.isActive
import com.lagradost.cloudstream3.remote.tracksPayloadFrom
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.IPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.Socket

/**
 * TV-side hub (plan §6.3): registry of SUBSCRIBE'd phone sockets, the active player,
 * and event broadcasting. [PlaybackReporter] is the thin facade GeneratorPlayer talks to;
 * the server talks to this hub directly for PLAY stash + PLAYER_CMD routing.
 *
 * Delivery is FIFO per subscriber: every subscriber owns one bounded [SubscriberEventQueue]
 * drained by a single writer coroutine, so frames leave in the order they were enqueued and
 * enqueueing never blocks the player or main thread. All broadcasts enqueue under the hub
 * lock, so concurrent broadcasts serialize into one deterministic global order (every
 * subscriber sees the same sequence) and a register's replay can never be overtaken by a live
 * frame (replay-before-live). On overflow the slow subscriber is retired and reconnects for
 * a fresh replay, keeping memory bounded without silently discarding an ordered event.
 */
object NowPlayingHub {
    private const val STATE_THROTTLE_MS = 10_000L

    private class Subscriber(
        val deviceId: String,
        val socket: Socket,
        var capabilities: Set<String>,
        var transportKey: ByteArray?,
    ) {
        val queue = SubscriberEventQueue()
        var writerJob: Job? = null

        fun enqueue(event: RemoteEvent): Boolean = queue.enqueue(event)

        /** Retires this subscriber: stops its writer, closes its queue, closes the socket. */
        fun close() {
            queue.close()
            writerJob?.cancel()
            runCatching { socket.close() }
        }
    }

    private val lock = Any()
    private val subscribers = linkedMapOf<String, Subscriber>() // deviceId -> event subscriber (one per phone)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: IPlayer? = null
    private var currentPlay: PlayPayload? = null
    private var currentSourceIndex = 0
    private var currentSubtitleIndex: Int? = null
    private var reportedTitle: String? = null
    private var reportedStreamName: String? = null
    private var lastState: NowPlayingPayload? = null
    private var lastInputContext: InputContextPayload? = null
    private var lastTracks: TracksPayload? = null
    private var lastBroadcastMs = 0L
    private var lastStateKind = NowPlayingPayload.State.IDLE

    // ------------------------------------------------------------------
    // Subscribers
    // ------------------------------------------------------------------

    fun registerSubscriber(
        deviceId: String,
        socket: Socket,
        capabilities: Set<String> = emptySet(),
        transportKey: ByteArray? = null,
    ) {
        synchronized(lock) {
            val previous = subscribers[deviceId]
            if (previous != null && previous.socket === socket) {
                // Same socket re-registers (double SUBSCRIBE): keep the existing subscriber,
                // its queue and its writer so FIFO is preserved; only the advertised
                // capabilities may change. No replay needed - the queue is already live.
                previous.capabilities = capabilities
                previous.transportKey = transportKey
                return
            }
            val subscriber = Subscriber(deviceId, socket, capabilities, transportKey)
            val replaced = subscribers.put(deviceId, subscriber)
            if (replaced != null) {
                // Replaced on re-subscribe (new socket): close the old writer + socket so its
                // read loop (and 30 s timeout) does not linger (review R7).
                replaced.close()
            }
            // Replay-before-live: enqueue the stored snapshots into the new queue under the
            // same lock every broadcast enqueue takes, so no live event can jump ahead of the
            // replay (FIFO per subscriber, deterministic global order). A fresh queue with
            // capacity 32 can never overflow on the 1-2 replay frames.
            lastState?.let { state ->
                subscriber.enqueue(RemoteEvent(kind = RemoteEvent.Kind.PLAYBACK_STATE, nowPlaying = state))
            }
            // Re-send the last input context to opt-in phones (C0): a phone that subscribes
            // while the TV search is already focused must not stay blind until the next event.
            if (canReceiveInputContext(capabilities)) {
                lastInputContext?.let { payload ->
                    subscriber.enqueue(RemoteEvent(kind = RemoteEvent.Kind.INPUT_CONTEXT, inputContext = payload))
                }
            }
            // Re-send the last renderer tracks to opt-in phones (F4a): a phone that subscribes
            // while a player is already active must see the track lists immediately.
            if (canReceiveTracks(capabilities)) {
                lastTracks?.let { payload ->
                    subscriber.enqueue(RemoteEvent(kind = RemoteEvent.Kind.TRACKS_AVAILABLE, tracks = payload))
                }
            }
            subscriber.writerJob = scope.launch { writerLoop(subscriber) }
        }
    }

    fun unregisterSubscriber(deviceId: String, socket: Socket) {
        val removed = synchronized(lock) {
            val candidate = subscribers[deviceId]
            if (candidate != null && candidate.socket === socket) {
                subscribers.remove(deviceId)
                candidate
            } else {
                null
            }
        }
        removed?.close()
    }

    /** Revokes every event channel belonging to one phone, including the socket itself. */
    fun revokeDevice(deviceId: String) {
        val removed = synchronized(lock) { subscribers.remove(deviceId) }
        removed?.close()
    }

    /** Closes all event channels during server shutdown or control revocation. */
    fun clearSubscribers() {
        val removed = synchronized(lock) {
            subscribers.values.toList().also { subscribers.clear() }
        }
        removed.forEach(Subscriber::close)
    }

    /** Test seam: number of live subscribers (used by unit tests to observe dead-socket cleanup). */
    internal fun subscriberCount(): Int = synchronized(lock) { subscribers.size }

    /**
     * Single writer per subscriber: drains the bounded queue in FIFO order and removes the
     * subscriber when a frame cannot be written (dead socket), so it never lingers.
     */
    private suspend fun writerLoop(sub: Subscriber) {
        while (true) {
            val event = sub.queue.receive() ?: break
            val ok = runCatching { writeEvent(sub, event) }.isSuccess
            if (!ok) {
                removeDeadSubscriber(sub)
                break
            }
        }
    }

    private fun removeDeadSubscriber(sub: Subscriber) {
        val removed = synchronized(lock) {
            subscribers.entries.removeAll { it.value === sub }
        }
        // Close outside the lock; a replaced subscriber (already closed) is left alone.
        if (removed) sub.close()
    }

    /** Per-subscriber gate: only phones that advertised CAP_INPUT_CONTEXT may see it. */
    internal fun canReceiveInputContext(capabilities: Set<String>): Boolean =
        DeviceInfo.CAP_INPUT_CONTEXT in capabilities

    /** Per-subscriber gate: only phones that advertised CAP_TRACKS may see renderer tracks. */
    internal fun canReceiveTracks(capabilities: Set<String>): Boolean =
        DeviceInfo.CAP_TRACKS in capabilities

    // ------------------------------------------------------------------
    // Player
    // ------------------------------------------------------------------

    /** Stash of the last PlayPayload so state events carry title/poster before the player emits progress. */
    fun stashPlay(payload: PlayPayload) {
        synchronized(lock) {
            val previous = currentPlay
            val isSourceRestart = previous != null && payload.links.size == 1 &&
                previous.links.any { it.url == payload.links.first().url }
            if (isSourceRestart) {
                currentSourceIndex = previous.links.indexOfFirst { it.url == payload.links.first().url }
                currentPlay = previous.copy(positionMs = payload.positionMs)
            } else {
                currentPlay = payload
                currentSourceIndex = 0
                currentSubtitleIndex = null
            }
        }
    }

    fun registerPlayer(player: IPlayer) {
        synchronized(lock) {
            this.player = player
            lastStateKind = NowPlayingPayload.State.IDLE
            lastBroadcastMs = 0
        }
        val duration = player.getDuration() ?: 0L
        val state = if (duration > 0L) playingState(player) else NowPlayingPayload.State.BUFFERING
        reportState(player.getPosition() ?: 0L, duration.coerceAtLeast(0L), state)
        // F4a: expose the renderer tracks as soon as the player registers.
        runCatching { broadcastTracks(tracksPayloadFrom(player.getVideoTracks())) }
    }

    fun unregisterPlayer(expectedPlayer: IPlayer? = null) {
        val hadPlayer = synchronized(lock) {
            if (expectedPlayer != null && player !== expectedPlayer) return@synchronized false
            val had = player != null
            player = null
            currentPlay = null
            currentSourceIndex = 0
            currentSubtitleIndex = null
            reportedTitle = null
            reportedStreamName = null
            lastState = null
            lastTracks = null
            lastStateKind = NowPlayingPayload.State.IDLE
            had
        }
        // Idempotent: exitPlayer() and onDestroy() both call this (review runtime #7);
        // only the first call broadcasts PLAYER_GONE.
        if (hadPlayer) broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
    }

    /** Throttled progress reporting; state transitions flush immediately (plan §6.3). */
    fun reportState(position: Long, duration: Long, state: NowPlayingPayload.State) {
        val now = System.currentTimeMillis()
        val (changed, registered) = synchronized(lock) {
            val isSame = player != null
            val changedState = state != lastStateKind
            lastStateKind = state
            changedState to isSame
        }
        if (!registered) return
        if (!changed && now - lastBroadcastMs < STATE_THROTTLE_MS) return

        val snapshot = synchronized(lock) {
            val play = currentPlay
            val speed = runCatching { player?.getPlaybackSpeed() ?: 1f }.getOrDefault(1f)
            NowPlayingPayload(
                mediaId = play?.mediaId,
                title = play?.title ?: reportedTitle,
                episodeName = play?.title ?: reportedTitle,
                streamName = reportedStreamName ?: play?.links?.getOrNull(currentSourceIndex)?.name,
                poster = play?.poster,
                positionMs = position,
                durationMs = duration,
                state = state,
                speed = speed,
            ).also { lastState = it; lastBroadcastMs = now }
        }
        broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYBACK_STATE, nowPlaying = snapshot))
    }

    /** Supplies a local-TV title/stream fallback when playback did not originate from the phone. */
    fun reportMetadata(title: String?, streamName: String?) {
        val state = synchronized(lock) {
            reportedTitle = title?.takeIf { it.isNotBlank() }
            reportedStreamName = streamName?.takeIf { it.isNotBlank() }
            lastBroadcastMs = 0L
            lastState?.state?.takeIf { it.isActive }
        }
        state ?: return
        val currentPlayer = synchronized(lock) { player } ?: return
        reportState(
            position = currentPlayer.getPosition() ?: 0L,
            duration = (currentPlayer.getDuration() ?: 0L).coerceAtLeast(0L),
            state = state,
        )
    }

    /** Publishes the TV player's full local source/subtitle menus for companion control. */
    fun reportPlaybackChoices(payload: PlayPayload) {
        synchronized(lock) {
            val previous = currentPlay
            currentPlay = if (previous == null) {
                payload
            } else {
                previous.copy(
                    links = payload.links.ifEmpty { previous.links },
                    subtitles = payload.subtitles.ifEmpty { previous.subtitles },
                    title = previous.title ?: payload.title,
                    poster = previous.poster ?: payload.poster,
                )
            }
            currentSourceIndex = currentSourceIndex.coerceIn(0, maxOf(0, currentPlay?.links?.lastIndex ?: 0))
            currentSubtitleIndex = currentSubtitleIndex?.takeIf {
                it in currentPlay?.subtitles.orEmpty().indices
            }
        }
        val target = synchronized(lock) { player } ?: return
        runCatching { broadcastTracks(tracksPayloadFrom(target.getVideoTracks())) }
    }

    fun currentState(): NowPlayingPayload? = synchronized(lock) { lastState }

    /** Provider apiName of the currently playing link (used to guard extension sync, plan §12). */
    fun currentPlaySource(): String? =
        synchronized(lock) { currentPlay?.links?.firstOrNull()?.source }

    fun playingState(player: IPlayer): NowPlayingPayload.State =
        runCatching { if (player.getIsPlaying()) NowPlayingPayload.State.PLAYING else NowPlayingPayload.State.PAUSED }
            .getOrDefault(NowPlayingPayload.State.PAUSED)

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    fun routeCommand(cmd: PlayerCmdPayload) {
        mainHandler.post {
            val target = synchronized(lock) { player }
            when (cmd.action) {
                PlayerCmdPayload.Action.PAUSE -> target?.handleEvent(CSPlayerEvent.Pause)
                PlayerCmdPayload.Action.RESUME -> target?.handleEvent(CSPlayerEvent.Play)
                PlayerCmdPayload.Action.PLAY_PAUSE -> target?.handleEvent(CSPlayerEvent.PlayPauseToggle)
                PlayerCmdPayload.Action.SEEK_TO -> cmd.positionMs?.let { target?.seekTo(it) }
                PlayerCmdPayload.Action.SEEK_BY -> cmd.deltaMs?.let { delta ->
                    target?.getPosition()?.let { target.seekTo((it + delta).coerceAtLeast(0L)) }
                }
                PlayerCmdPayload.Action.SET_SPEED -> cmd.speed?.let { target?.setPlaybackSpeed(it) }
                PlayerCmdPayload.Action.STOP -> {
                    val activity = CommonActivity.activity
                    if (activity is androidx.activity.ComponentActivity) {
                        activity.onBackPressedDispatcher.onBackPressed()
                    } else {
                        @Suppress("DEPRECATION")
                        activity?.onBackPressed()
                    }
                }
                PlayerCmdPayload.Action.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                PlayerCmdPayload.Action.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                PlayerCmdPayload.Action.MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                // Unknown action (newer phone): safe no-op, never crash the socket.
                PlayerCmdPayload.Action.UNKNOWN -> Unit
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    // ------------------------------------------------------------------
    // F4a: renderer track selection (video/audio by id, subtitle disable only)
    // ------------------------------------------------------------------

    /**
     * Applies a SELECT_TRACK on the main thread against the active player. Video and audio are
     * selected by their renderer [SelectTrackPayload.id] (resolved through the player contract:
     * setMaxVideoSize(id) / setPreferredAudioTrack(language, id, formatIndex)). TEXT is a
     * subtitle *disable* only (empty active set) - text tracks are never claimed by wire id
     * because the IPlayer contract selects subtitles by SubtitleData, not renderer id. After
     * applying, the authoritative track list is re-broadcast so the phone re-renders; the player
     * also emits TracksChangedEvent -> onTracksInfoChanged -> broadcastTracks.
     */
    fun selectTrack(select: SelectTrackPayload): Boolean {
        val target = synchronized(lock) { player } ?: return false
        val tracks = runCatching { target.getVideoTracks() }.getOrNull() ?: return false
        if (!isSelectionAvailable(select, tracks)) return false
        val applied = runCatching {
            when (select.type) {
                SelectTrackPayload.TrackType.VIDEO -> {
                    val video = tracks.allVideoTracks.firstOrNull { it.id == select.id }
                        ?: return@runCatching false
                    target.setMaxVideoSize(
                        width = video.width ?: Int.MAX_VALUE,
                        height = video.height ?: Int.MAX_VALUE,
                        id = video.id,
                    )
                    true
                }
                SelectTrackPayload.TrackType.AUDIO -> {
                    val audio = tracks.allAudioTracks.firstOrNull { it.id == select.id }
                        ?: return@runCatching false
                    target.setPreferredAudioTrack(audio.language, audio.id, audio.formatIndex)
                    true
                }
                SelectTrackPayload.TrackType.TEXT -> {
                    target.setActiveSubtitles(emptySet())
                    true
                }
                SelectTrackPayload.TrackType.UNKNOWN -> false
            }
        }.getOrDefault(false)
        if (applied) runCatching { broadcastTracks(tracksPayloadFrom(target.getVideoTracks())) }
        return applied
    }

    /** Returns a one-link restart payload while retaining the full source menu in the hub. */
    fun selectSource(index: Int): PlayPayload? = synchronized(lock) {
        val play = currentPlay ?: return@synchronized null
        val link = play.links.getOrNull(index) ?: return@synchronized null
        currentSourceIndex = index
        play.copy(links = listOf(link), positionMs = player?.getPosition())
    }

    /** Applies one companion-provided subtitle file, or disables subtitles for a null index. */
    fun selectSubtitle(index: Int?): Boolean {
        val target = synchronized(lock) { player } ?: return false
        val subtitle = synchronized(lock) { currentPlay?.subtitles?.getOrNull(index ?: -1) }
        if (index != null && subtitle == null) return false
        val applied = runCatching {
            target.setActiveSubtitles(subtitle?.let { setOf(it.toSubtitleData()) } ?: emptySet())
            true
        }.getOrDefault(false)
        if (applied) {
            synchronized(lock) { currentSubtitleIndex = index }
            runCatching { broadcastTracks(tracksPayloadFrom(target.getVideoTracks())) }
        }
        return applied
    }

    private fun withPlaybackChoices(tracks: TracksPayload): TracksPayload = synchronized(lock) {
        val play = currentPlay
        tracks.copy(
            sources = play?.links?.mapIndexed { index, link ->
                PlaybackChoice(index, link.name ?: "Source ${index + 1}", sourceDetail(link))
            }.orEmpty(),
            currentSourceIndex = currentSourceIndex,
            subtitles = play?.subtitles?.mapIndexed { index, subtitle ->
                PlaybackChoice(index, subtitle.name ?: "Subtitle ${index + 1}", subtitle.mimeType)
            }.orEmpty(),
            currentSubtitleIndex = currentSubtitleIndex,
        )
    }

    private fun sourceDetail(link: CloudStreamPackage.MinimalVideoLink): String? =
        listOfNotNull(link.quality?.let { "${it}p" }, link.mimeType.substringAfter('/').uppercase())
            .joinToString(" • ").ifBlank { null }

    internal fun isSelectionAvailable(
        select: SelectTrackPayload,
        tracks: com.lagradost.cloudstream3.ui.player.CurrentTracks,
    ): Boolean = when (select.type) {
        SelectTrackPayload.TrackType.VIDEO ->
            tracks.allVideoTracks.any { it.id != null && it.id == select.id }
        SelectTrackPayload.TrackType.AUDIO ->
            tracks.allAudioTracks.any { it.id != null && it.id == select.id }
        SelectTrackPayload.TrackType.TEXT -> select.id == null
        SelectTrackPayload.TrackType.UNKNOWN -> false
    }

    // ------------------------------------------------------------------
    // Broadcasting
    // ------------------------------------------------------------------

    fun broadcast(event: RemoteEvent) {
        // Enqueue to every subscriber under the hub lock so all subscribers receive events in
        // one deterministic global order (concurrent broadcasts serialize), and so a register's
        // replay can never be overtaken by a live frame (replay-before-live). A subscriber whose
        // bounded queue overflows with a non-replaceable frame is retired (bounded, safe failure
        // policy) - never silently dropped - and closed outside the lock.
        val retirees = synchronized(lock) {
            val toRetire = ArrayList<Subscriber>(0)
            val iterator = subscribers.values.iterator()
            while (iterator.hasNext()) {
                val sub = iterator.next()
                if (!sub.enqueue(event)) {
                    iterator.remove()
                    toRetire.add(sub)
                }
            }
            toRetire
        }
        retirees.forEach { it.close() }
    }

    /**
     * INPUT_CONTEXT is opt-in: only streamed to subscribers whose SUBSCRIBE advertised
     * [DeviceInfo.CAP_INPUT_CONTEXT], so a legacy phone never sees the new kind at all.
     */
    fun broadcastInputContext(payload: InputContextPayload) {
        val retirees = synchronized(lock) {
            lastInputContext = payload
            val toRetire = ArrayList<Subscriber>(0)
            val iterator = subscribers.values.iterator()
            while (iterator.hasNext()) {
                val sub = iterator.next()
                if (!canReceiveInputContext(sub.capabilities)) continue
                val event = RemoteEvent(kind = RemoteEvent.Kind.INPUT_CONTEXT, inputContext = payload)
                if (!sub.enqueue(event)) {
                    iterator.remove()
                    toRetire.add(sub)
                }
            }
            toRetire
        }
        retirees.forEach { it.close() }
    }

    /**
     * TRACKS_AVAILABLE is opt-in (F4a): only streamed to subscribers whose SUBSCRIBE advertised
     * [DeviceInfo.CAP_TRACKS], so a legacy phone never sees the new kind at all.
     */
    fun broadcastTracks(payload: TracksPayload) {
        val effectivePayload = withPlaybackChoices(payload)
        val retirees = synchronized(lock) {
            lastTracks = effectivePayload
            val toRetire = ArrayList<Subscriber>(0)
            val iterator = subscribers.values.iterator()
            while (iterator.hasNext()) {
                val sub = iterator.next()
                if (!canReceiveTracks(sub.capabilities)) continue
                val event = RemoteEvent(kind = RemoteEvent.Kind.TRACKS_AVAILABLE, tracks = effectivePayload)
                if (!sub.enqueue(event)) {
                    iterator.remove()
                    toRetire.add(sub)
                }
            }
            toRetire
        }
        retirees.forEach { it.close() }
    }

    private fun writeEvent(subscriber: Subscriber, event: RemoteEvent) {
        // One frame per socket at a time: keep frames atomic.
        synchronized(subscriber.socket) {
            val output = DataOutputStream(subscriber.socket.getOutputStream())
            val element = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(event)
            )
            val key = subscriber.transportKey
            if (key == null) {
                LanRemoteProtocol.writeJson(output, element)
            } else {
                LanRemoteProtocol.writeEncrypted(output, key, subscriber.deviceId, element)
            }
        }
    }
}
