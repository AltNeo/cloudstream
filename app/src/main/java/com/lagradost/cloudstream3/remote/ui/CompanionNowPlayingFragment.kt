package com.lagradost.cloudstream3.remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.SheetCompanionNowPlayingBinding
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.DeviceInfo
import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.SelectTrackPayload
import com.lagradost.cloudstream3.remote.SelectPlaybackOptionPayload
import com.lagradost.cloudstream3.remote.PlaybackChoice
import com.lagradost.cloudstream3.remote.TrackInfo
import com.lagradost.cloudstream3.remote.TracksPayload
import com.lagradost.cloudstream3.remote.formatPlaybackTime
import com.lagradost.cloudstream3.remote.isActive
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone in-app now-playing controller (plan §6.5): poster, title, seek bar and transport
 * buttons, all fed by [CompanionSessionManager.nowPlaying] and sent back as PLAYER_CMD.
 * Dismisses itself when the TV reports the player is gone.
 *
 * While PLAYING the seekbar position is driven by local monotonic interpolation (checkpoint 2):
 * between the TV's throttled (10 s) state events the position advances on-device at the
 * reported speed, so it feels live without any extra network traffic. Dragging sends only a
 * single SEEK_TO on release — there is deliberately no SEEK_PREVIEW traffic.
 */
class CompanionNowPlayingFragment : BottomSheetDialogFragment() {
    private var _binding: SheetCompanionNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var isSeeking = false

    /** Local tick cadence for the interpolated position while the sheet is open. */
    private companion object {
        const val TICK_MS = 500L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetCompanionNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.nowPlayingRewind.setOnClickListener {
            sendCmd(PlayerCmdPayload.Action.SEEK_BY, deltaMs = -10_000L)
        }
        binding.nowPlayingForward.setOnClickListener {
            sendCmd(PlayerCmdPayload.Action.SEEK_BY, deltaMs = 10_000L)
        }
        binding.nowPlayingPlayPause.setOnClickListener {
            sendCmd(PlayerCmdPayload.Action.PLAY_PAUSE)
        }
        binding.nowPlayingStop.setOnClickListener {
            sendCmd(PlayerCmdPayload.Action.STOP)
            dismiss()
        }
        binding.nowPlayingVideoTrack.setOnClickListener {
            val tracks = CompanionSessionManager.tracks.value ?: return@setOnClickListener
            showTrackPicker(
                R.string.companion_select_video_track,
                tracks.videoTracks,
                tracks.currentVideoId,
                SelectTrackPayload.TrackType.VIDEO,
            )
        }
        binding.nowPlayingSource.setOnClickListener {
            val tracks = CompanionSessionManager.tracks.value ?: return@setOnClickListener
            showSourcePicker(tracks)
        }
        binding.nowPlayingAudioTrack.setOnClickListener {
            val tracks = CompanionSessionManager.tracks.value ?: return@setOnClickListener
            showTrackPicker(
                R.string.companion_select_audio_track,
                tracks.audioTracks,
                tracks.currentAudioId,
                SelectTrackPayload.TrackType.AUDIO,
            )
        }
        binding.nowPlayingDisableSubtitles.setOnClickListener {
            val tracks = CompanionSessionManager.tracks.value ?: return@setOnClickListener
            if (tracks.subtitles.isNotEmpty()) showSubtitlePicker(tracks)
            else selectTrack(SelectTrackPayload(SelectTrackPayload.TrackType.TEXT))
        }
        binding.nowPlayingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Local drag preview only: the time label follows the thumb while dragging, but
                // the TV is told exactly once, on release, with SEEK_TO (no SEEK_PREVIEW, plan F3
                // / checkpoint 2).
                if (fromUser && isSeeking) {
                    binding.nowPlayingPosition.text = getString(
                        R.string.companion_now_playing_position_format,
                        formatPlaybackTime(progress.toLong()),
                        formatPlaybackTime(binding.nowPlayingSeek.max.toLong()),
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                sendCmd(PlayerCmdPayload.Action.SEEK_TO, positionMs = seekBar?.progress?.toLong())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            CompanionSessionManager.nowPlaying.collect { payload -> render(payload) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            CompanionSessionManager.state.collect { state ->
                renderTracks(state.tracks, state.tvCapabilities)
            }
        }
        // Live-position ticker: while the sheet is open, re-render just the seekbar and time
        // label from the locally interpolated position (checkpoint 2). Never fights the user's
        // drag: while isSeeking the thumb stays where the user put it.
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val payload = CompanionSessionManager.nowPlaying.value
                if (payload != null && _binding != null) updatePosition(payload)
                delay(TICK_MS)
            }
        }
    }

    private fun renderTracks(tracks: TracksPayload?, capabilities: Set<String>) {
        if (tracks == null) {
            binding.nowPlayingTracks.isVisible = false
            return
        }
        val capable = DeviceInfo.CAP_TRACKS in capabilities
        val showVideo = capable && tracks.videoTracks.isNotEmpty()
        val showAudio = capable && tracks.audioTracks.isNotEmpty()
        val canChoose = DeviceInfo.CAP_PLAYBACK_CHOICES in capabilities
        val showSource = capable && canChoose && tracks.sources.isNotEmpty()
        val showSubtitles = capable && ((canChoose && tracks.subtitles.isNotEmpty()) ||
            (tracks.hasTextTracks && tracks.subtitlesEnabled))
        binding.nowPlayingTracks.isVisible = showSource || showVideo || showAudio || showSubtitles
        binding.nowPlayingSource.isVisible = showSource
        binding.nowPlayingVideoTrack.isVisible = showVideo
        binding.nowPlayingAudioTrack.isVisible = showAudio
        binding.nowPlayingDisableSubtitles.isVisible = showSubtitles
        if (showSource) binding.nowPlayingSource.text = getString(
            R.string.companion_choice_count, getString(R.string.companion_sources), tracks.sources.size
        )
        if (showSubtitles) binding.nowPlayingDisableSubtitles.text = getString(
            R.string.companion_choice_count,
            getString(R.string.companion_subtitles),
            tracks.subtitles.size,
        )
    }

    private fun showTrackPicker(
        title: Int,
        tracks: List<TrackInfo>,
        selectedId: String?,
        type: SelectTrackPayload.TrackType,
    ) {
        val labels = tracks.map { listOfNotNull(it.label, it.extra).joinToString(" · ") }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == selectedId }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectTrack(SelectTrackPayload(type, tracks[which].id))
                dialog.dismiss()
            }
            .show()
    }

    private fun selectTrack(selection: SelectTrackPayload) {
        lifecycleScope.launch {
            val reply = runCatching {
                CompanionSessionManager.send(RemoteMessageType.SELECT_TRACK, selection)
            }.getOrNull()
            if (reply?.accepted != true) {
                CommonActivity.showToast(reply?.error ?: getString(R.string.remote_command_failed))
            }
        }
    }

    private fun showSourcePicker(tracks: TracksPayload) = showPlaybackChoicePicker(
        R.string.companion_select_source,
        tracks.sources,
        tracks.currentSourceIndex,
        SelectPlaybackOptionPayload.Type.SOURCE,
        allowOff = false,
    )

    private fun showSubtitlePicker(tracks: TracksPayload) = showPlaybackChoicePicker(
        R.string.companion_select_subtitle,
        tracks.subtitles,
        tracks.currentSubtitleIndex,
        SelectPlaybackOptionPayload.Type.SUBTITLE,
        allowOff = true,
    )

    private fun showPlaybackChoicePicker(
        title: Int,
        choices: List<PlaybackChoice>,
        selectedIndex: Int?,
        type: SelectPlaybackOptionPayload.Type,
        allowOff: Boolean,
    ) {
        val labels = buildList {
            if (allowOff) add(getString(R.string.companion_subtitles_off))
            addAll(choices.map { listOfNotNull(it.label, it.detail).joinToString(" · ") })
        }.toTypedArray()
        val checked = when {
            allowOff && selectedIndex == null -> 0
            allowOff -> (selectedIndex ?: 0) + 1
            else -> selectedIndex ?: -1
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val index = if (allowOff && which == 0) null else choices[which - if (allowOff) 1 else 0].index
                selectPlaybackOption(SelectPlaybackOptionPayload(type, index))
                dialog.dismiss()
            }
            .show()
    }

    private fun selectPlaybackOption(selection: SelectPlaybackOptionPayload) {
        lifecycleScope.launch {
            val reply = runCatching {
                CompanionSessionManager.send(RemoteMessageType.SELECT_PLAYBACK_OPTION, selection)
            }.getOrNull()
            if (reply?.accepted != true) {
                CommonActivity.showToast(reply?.error ?: getString(R.string.remote_command_failed))
            }
        }
    }

    private fun render(payload: NowPlayingPayload?) {
        if (payload == null || !payload.state.isActive) {
            // StateFlow can deliver PLAYER_GONE after the activity has saved its FragmentManager
            // state (for example while the phone is backgrounded). This sheet is transient, so
            // allowing state loss is correct and prevents an uncaught main-thread crash.
            dismissAllowingStateLoss()
            return
        }
        binding.nowPlayingTitle.text = payload.title ?: payload.episodeName ?: ""
        binding.nowPlayingStatus.text = buildString {
            payload.episodeName?.let { append(it); append(" · ") }
            append(getString(R.string.companion_now_playing_status))
        }
        binding.nowPlayingStream.text = payload.streamName
        binding.nowPlayingStream.isVisible = !payload.streamName.isNullOrBlank()
        binding.nowPlayingPoster.loadImage(payload.poster) {
            error(getImageFromDrawable(requireContext(), R.drawable.ic_baseline_tv_24))
        }
        binding.nowPlayingPlayPause.setImageResource(
            if (payload.state == NowPlayingPayload.State.PLAYING) {
                R.drawable.ic_baseline_pause_24
            } else {
                R.drawable.ic_baseline_play_arrow_24
            }
        )
        if (payload.durationMs > 0) {
            binding.nowPlayingSeek.isVisible = true
            binding.nowPlayingSeek.max = payload.durationMs.toInt()
            updatePosition(payload)
        } else {
            binding.nowPlayingSeek.isVisible = false
            binding.nowPlayingPosition.text = ""
        }
    }

    /**
     * Renders just the seekbar + time label from the locally interpolated position so the
     * position feels live between the TV's throttled state events (checkpoint 2). While the
     * user is dragging, the thumb stays put and the label shows the dragged value (set in
     * onProgressChanged); on release a single SEEK_TO is sent.
     */
    private fun updatePosition(payload: NowPlayingPayload) {
        val live = CompanionSessionManager.livePositionMs() ?: payload.positionMs
        if (!isSeeking) {
            binding.nowPlayingSeek.progress = live.toInt().coerceIn(0, binding.nowPlayingSeek.max)
        }
        binding.nowPlayingPosition.text = getString(
            R.string.companion_now_playing_position_format,
            formatPlaybackTime(live),
            formatPlaybackTime(payload.durationMs),
        )
    }

    private fun sendCmd(
        action: PlayerCmdPayload.Action,
        deltaMs: Long? = null,
        positionMs: Long? = null,
    ) {
        lifecycleScope.launch {
            runCatching {
                CompanionSessionManager.send(
                    RemoteMessageType.PLAYER_CMD,
                    PlayerCmdPayload(action, positionMs = positionMs, deltaMs = deltaMs),
                )
            }.onSuccess { response ->
                if (!response.accepted) {
                    CommonActivity.showToast(response.error ?: getString(R.string.remote_command_failed))
                }
            }.onFailure { error ->
                CommonActivity.showToast(error.message ?: getString(R.string.remote_connection_failed))
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
