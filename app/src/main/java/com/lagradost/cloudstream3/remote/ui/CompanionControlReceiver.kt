package com.lagradost.cloudstream3.remote.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.RemoteMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns now-playing notification actions into PLAYER_CMD envelopes to the active TV
 * (plan §6.5). Uses goAsync() so the broadcast process outlives onReceive().
 */
class CompanionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cmd = when (action) {
                    ACTION_PLAY_PAUSE -> PlayerCmdPayload(PlayerCmdPayload.Action.PLAY_PAUSE)
                    ACTION_SEEK_FORWARD -> PlayerCmdPayload(
                        PlayerCmdPayload.Action.SEEK_BY, deltaMs = 10_000L
                    )
                    ACTION_SEEK_BACK -> PlayerCmdPayload(
                        PlayerCmdPayload.Action.SEEK_BY, deltaMs = -10_000L
                    )
                    ACTION_STOP -> PlayerCmdPayload(PlayerCmdPayload.Action.STOP)
                    else -> null
                }
                if (cmd != null) {
                    runCatching {
                        CompanionSessionManager.send(RemoteMessageType.PLAYER_CMD, cmd)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_SEEK_FORWARD = "seek_forward"
        const val ACTION_SEEK_BACK = "seek_back"
        const val ACTION_STOP = "stop"
    }
}
