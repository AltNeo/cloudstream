package com.lagradost.cloudstream3.ui

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.PairingSession

/**
 * Full-screen PIN overlay shown on the TV when a phone starts pairing (plan §4).
 * Works over the player too (it is a Dialog on CommonActivity.activity).
 *
 * Cold-start behavior (review R8): when the TV app was backgrounded/killed, [show]
 * retries until [CommonActivity.activity] exists or the session expires, so the PIN
 * is always displayed instead of silently vanishing.
 *
 * The overlay is cancelable (back button + on-screen Cancel): the user can dismiss a
 * pairing they did not initiate (review S2), which also removes the pairing session.
 */
object PairingOverlayDialog {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDialog: Dialog? = null
    private var currentSession: PairingSession? = null
    private var expiryRunnable: Runnable? = null

    fun show(session: PairingSession) {
        currentSession = session
        mainHandler.post {
            if (CommonActivity.activity == null) {
                if (session.isExpired || currentSession !== session) return@post
                mainHandler.postDelayed({ show(session) }, 250)
                return@post
            }
            showInternal(session)
        }
    }

    private fun showInternal(session: PairingSession) {
        val activity = CommonActivity.activity ?: return
        if (currentSession !== session) return
        // Dismiss the previous overlay inline (a posted dismiss would race this show).
        currentDialog?.dismiss()
        currentDialog = null

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setCancelable(true)
            setOnCancelListener { cancelSession(session) }
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6000000.toInt())
            setPadding(48, 48, 48, 48)
        }
        layout.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.companion_pairing_title)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 26f
                gravity = Gravity.CENTER
            }
        )
        layout.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.companion_pairing_from, session.phoneName)
                setTextColor(0xFFB0BEC5.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
            }
        )
        layout.addView(
            TextView(activity).apply {
                text = session.pin
                setTextColor(0xFF4FC3F7.toInt())
                textSize = 72f
                gravity = Gravity.CENTER
                letterSpacing = 0.3f
            }
        )
        layout.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.companion_pairing_instructions)
                setTextColor(0xFFB0BEC5.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
            }
        )
        layout.addView(
            TextView(activity).apply {
                text = activity.getString(android.R.string.cancel)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 0)
                setOnClickListener { cancelSession(session) }
            }
        )
        dialog.setContentView(layout)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        currentDialog = dialog
        dialog.show()

        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        expiryRunnable = Runnable {
            if (currentSession?.sessionId == session.sessionId) dismiss()
        }
        mainHandler.postDelayed(expiryRunnable!!, (session.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelSession(session: PairingSession) {
        PairingManager.removePairingSession(session.sessionId)
        dismiss()
    }

    fun dismiss() {
        mainHandler.post {
            currentDialog?.dismiss()
            currentDialog = null
            currentSession = null
        }
    }
}
