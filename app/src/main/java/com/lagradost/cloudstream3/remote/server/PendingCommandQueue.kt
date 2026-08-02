package com.lagradost.cloudstream3.remote.server

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.remote.LanRemoteProtocol
import com.lagradost.cloudstream3.remote.OpenPagePayload
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import com.lagradost.cloudstream3.utils.AppContextUtils
import kotlinx.serialization.json.JsonObject

/**
 * Generalizes the old cold-start PLAY retry (plan §5.1): when the app is not on screen,
 * the last command (PLAY / OPEN_PAGE) is launched with the app and retried until the
 * activity exists, up to 10 attempts / 30 s expiry.
 */
object PendingCommandQueue {
    private const val EXPIRY_MS = 30_000L
    private const val MAX_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 1_500L

    private data class Pending(
        val type: RemoteMessageType,
        val payload: JsonObject?,
        val context: Context,
        val createdAtMs: Long,
        var attempts: Int = 0,
    )

    private val lock = Any()
    private var pending: Pending? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submit(type: RemoteMessageType, payload: JsonObject?, context: Context) {
        synchronized(lock) {
            pending = Pending(type, payload, context.applicationContext, System.currentTimeMillis())
        }
        tryRun()
    }

    private fun tryRun() {
        val item = synchronized(lock) { pending } ?: return
        if (System.currentTimeMillis() - item.createdAtMs > EXPIRY_MS) {
            synchronized(lock) { pending = null }
            return
        }
        val activity = CommonActivity.activity
        if (activity == null) {
            if (item.attempts >= MAX_ATTEMPTS) {
                synchronized(lock) { pending = null }
                return
            }
            item.attempts++
            launchApp(item.context)
            mainHandler.postDelayed({ tryRun() }, RETRY_DELAY_MS)
            return
        }
        runCommand(activity, item)
        synchronized(lock) { pending = null }
    }

    private fun runCommand(activity: android.app.Activity, item: Pending) {
        when (item.type) {
            RemoteMessageType.PLAY -> {
                val play = item.payload?.let {
                    runCatching { LanRemoteProtocol.json.decodeFromString<PlayPayload>(it.toString()) }.getOrNull()
                } ?: return
                PlaybackReporter.stashPlay(play)
                OfflinePlaybackHelper.playIntent(activity, playToIntent(play))
            }

            RemoteMessageType.OPEN_PAGE -> {
                val page = item.payload?.let {
                    runCatching { LanRemoteProtocol.json.decodeFromString<OpenPagePayload>(it.toString()) }.getOrNull()
                } ?: return
                val api = APIHolder.getApiFromNameNull(page.apiName)
                if (api == null) {
                    CommonActivity.showToast(
                        "Provider ${page.apiName} is not installed on this device"
                    )
                } else {
                    AppContextUtils.loadResult(page.url, page.apiName, api.name ?: page.apiName)
                }
            }

            else -> Unit
        }
    }

    private fun playToIntent(play: PlayPayload): Intent = Intent().apply {
        putExtra(
            CloudStreamPackage.LINKS_EXTRA,
            play.links.map { LanRemoteProtocol.json.encodeToString(it) }.toTypedArray(),
        )
        putExtra(
            CloudStreamPackage.SUBTITLE_EXTRA,
            play.subtitles.map { LanRemoteProtocol.json.encodeToString(it) }.toTypedArray(),
        )
        play.title?.let { putExtra(CloudStreamPackage.TITLE_EXTRA, it) }
        play.mediaId?.let { putExtra(CloudStreamPackage.ID_EXTRA, it) }
        play.positionMs?.let { putExtra(CloudStreamPackage.POSITION_EXTRA, it) }
        play.durationMs?.let { putExtra(CloudStreamPackage.DURATION_EXTRA, it) }
    }

    private fun launchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }
}
