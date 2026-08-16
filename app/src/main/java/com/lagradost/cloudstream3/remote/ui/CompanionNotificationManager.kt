package com.lagradost.cloudstream3.remote.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.formatPlaybackTime
import com.lagradost.cloudstream3.remote.isActive

/**
 * Minimal now-playing notification for the phone role (plan §6.5): shown while the paired
 * TV reports a live player, with transport actions that [CompanionControlReceiver] turns
 * into PLAYER_CMD envelopes. No MediaSession in v1 — the actions are plain broadcasts.
 */
object CompanionNotificationManager {
    const val CHANNEL_ID = "companion_now_playing"
    const val NOTIFICATION_ID = 46901

    /** Shows/updates the notification, or cancels it when playback ended or the state is gone. */
    fun update(context: Context, payload: NowPlayingPayload?) {
        if (payload == null || !payload.state.isActive) {
            cancel(context)
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val contentText = buildString {
            payload.episodeName?.let { append(it); append(" · ") }
            append(formatPlaybackTime(payload.positionMs))
            append(" / ")
            append(formatPlaybackTime(payload.durationMs))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_tv_24)
            .setContentTitle(payload.title ?: context.getString(R.string.companion_now_playing_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_baseline_fast_forward_24,
                context.getString(R.string.companion_seek_forward),
                controlIntent(context, CompanionControlReceiver.ACTION_SEEK_FORWARD),
            )
            .addAction(
                R.drawable.ic_baseline_pause_24,
                context.getString(R.string.remote_play_pause),
                controlIntent(context, CompanionControlReceiver.ACTION_PLAY_PAUSE),
            )
            .addAction(
                R.drawable.baseline_stop_24,
                context.getString(R.string.companion_stop_playback),
                controlIntent(context, CompanionControlReceiver.ACTION_STOP),
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun controlIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, CompanionControlReceiver::class.java)
                .putExtra(CompanionControlReceiver.EXTRA_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.companion_now_playing_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.companion_now_playing_channel_desc)
                }
            )
        }
    }
}
