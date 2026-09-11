package com.lagradost.cloudstream3.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Storage for companion identity and pairing metadata.
 *
 * This file is deliberately separate from the app preferences. It is excluded from both
 * Android backup formats because it contains long-lived device identity material and paired
 * public keys. The crypto implementation may use [preferences] through its key-storage
 * adapter without coupling the rest of the app to the preference name.
 */
object CompanionPreferences {
    const val FILE_NAME = "companion_pairings"
    const val KEY_ENABLED = "enabled"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_TV_NAME = "tv_name"
    const val KEY_PAIRED_DEVICES = "paired_devices"
    private const val KEY_ENDPOINT_PREFIX = "endpoint_"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun deviceName(context: Context): String =
        preferences(context).getString(KEY_DEVICE_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    fun setDeviceName(context: Context, name: String) {
        preferences(context).edit { putString(KEY_DEVICE_NAME, name.trim()) }
    }

    fun endpoint(context: Context, peerId: String): String? =
        preferences(context).getString(KEY_ENDPOINT_PREFIX + peerId, null)

    fun setEndpoint(context: Context, peerId: String, endpoint: String) {
        preferences(context).edit { putString(KEY_ENDPOINT_PREFIX + peerId, endpoint) }
    }

    fun removeEndpoint(context: Context, peerId: String) {
        preferences(context).edit { remove(KEY_ENDPOINT_PREFIX + peerId) }
    }
}
