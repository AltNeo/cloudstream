package com.lagradost.cloudstream3.remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.SheetCompanionNowPlayingBinding
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.formatPlaybackTime
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.launch

/**
 * Phone in-app now-playing controller (plan §6.5): poster, title, seek bar and transport
 * buttons, all fed by [CompanionSessionManager.nowPlaying] and sent back as PLAYER_CMD.
 * Dismisses itself when the TV reports the player is gone.
 */
class CompanionNowPlayingFragment : BottomSheetDialogFragment() {
    private var _binding: SheetCompanionNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var isSeeking = false

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
        binding.nowPlayingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                sendCmd(PlayerCmdPayload.Action.SEEK_TO, positionMs = seekBar?.progress?.toLong())
            }
        })

        lifecycleScope.launch {
            CompanionSessionManager.nowPlaying.collect { payload -> render(payload) }
        }
    }

    private fun render(payload: NowPlayingPayload?) {
        if (payload == null ||
            payload.state == NowPlayingPayload.State.IDLE ||
            payload.state == NowPlayingPayload.State.ENDED
        ) {
            dismiss()
            return
        }
        binding.nowPlayingTitle.text = payload.title ?: payload.episodeName ?: ""
        binding.nowPlayingStatus.text = buildString {
            payload.episodeName?.let { append(it); append(" · ") }
            append(getString(R.string.companion_now_playing_status))
        }
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
            if (!isSeeking) {
                binding.nowPlayingSeek.progress =
                    payload.positionMs.toInt().coerceIn(0, binding.nowPlayingSeek.max)
            }
            binding.nowPlayingPosition.text = getString(
                R.string.companion_now_playing_position_format,
                formatPlaybackTime(payload.positionMs),
                formatPlaybackTime(payload.durationMs),
            )
        } else {
            binding.nowPlayingSeek.isVisible = false
            binding.nowPlayingPosition.text = ""
        }
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
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
