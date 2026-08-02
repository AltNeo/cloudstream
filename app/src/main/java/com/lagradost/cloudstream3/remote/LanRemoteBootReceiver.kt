package com.lagradost.cloudstream3.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LanRemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Gate on the receiver setting (default ON for TVs, OFF for phones) instead of
        // hardcoding the UI mode (plan §10).
        if (PairingManager.isControlAllowed(context)) {
            LanRemoteService.start(context)
        }
    }
}
