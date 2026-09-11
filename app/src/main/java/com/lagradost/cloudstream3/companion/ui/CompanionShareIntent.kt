package com.lagradost.cloudstream3.companion.ui

import android.content.Intent

/** Extracts one safe web URL from an ACTION_SEND text share. */
object CompanionShareIntent {
    private val urlPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

    fun extractUrl(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        return extractUrlFromText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    }

    fun extractUrlFromText(text: String?): String? {
        val candidate = urlPattern.find(text?.take(4096).orEmpty())?.value
            ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            ?: return null
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.equals("http", ignoreCase = true) != true &&
            uri.scheme?.equals("https", ignoreCase = true) != true
        ) return null
        if (uri.host.isNullOrBlank()) return null
        return candidate
    }
}
