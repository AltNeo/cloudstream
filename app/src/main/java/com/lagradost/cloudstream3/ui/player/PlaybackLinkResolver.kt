package com.lagradost.cloudstream3.ui.player

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Resolves provider pages on the browsing device before a link crosses to the TV. */
object PlaybackLinkResolver {
    private val mediaUrl = Regex(
        "(?i).*(?:\\.m3u8|\\.mpd|\\.(?:mp4|mkv|webm|avi|mov))(?:[?#].*)?$"
    )
    private val streamRequest = Regex(
        "(?i).*(?:/download(?:/|\\?)|/stream(?:/|\\?)|[?&](?:download|stream)=).*"
    )

    suspend fun resolve(
        context: Context,
        link: CloudStreamPackage.MinimalVideoLink,
    ):
        CloudStreamPackage.MinimalVideoLink? {
        if (PlaybackCoordinator.isTvCompatibleUrl(link.url.orEmpty())) return link

        val resolverUrl = link.extractorData
            ?.takeIf { PlaybackCoordinator.isTvCompatibleUrl(it) }
            ?: return null

        val resolved = resolveInPhoneWebView(context, resolverUrl, link.headers)
            ?: return null
        val resolvedUrl = resolved.url
        if (!PlaybackCoordinator.isTvCompatibleUrl(resolvedUrl)) return null

        return link.copy(
            url = resolvedUrl,
            mimeType = when {
                resolvedUrl.contains(".m3u8", ignoreCase = true) ->
                    ExtractorLinkType.M3U8.getMimeType()
                resolvedUrl.contains(".mpd", ignoreCase = true) ->
                    ExtractorLinkType.DASH.getMimeType()
                else -> ExtractorLinkType.VIDEO.getMimeType()
            },
            headers = link.headers + resolved.headers,
        )
    }

    private data class ResolvedRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    private suspend fun resolveInPhoneWebView(
        context: Context,
        resolverUrl: String,
        headers: Map<String, String>,
    ): ResolvedRequest? {
        val activity = context.getActivity() ?: return null
        return withTimeoutOrNull(120_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(activity)
                    val status = TextView(activity).apply {
                        setBackgroundColor(Color.argb(220, 0, 0, 0))
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(32, 0, 32, 0)
                        text = "Preparing stream on phone… Complete any verification to continue."
                    }
                    val container = FrameLayout(activity).apply {
                        addView(
                            webView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        addView(
                            status,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                112,
                                Gravity.TOP,
                            ),
                        )
                    }
                    val dialog = Dialog(
                        activity,
                        android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen,
                    ).apply {
                        setTitle("Preparing stream")
                        setContentView(container)
                    }

                    fun finish(result: ResolvedRequest?) {
                        if (!continuation.isActive) return
                        continuation.resume(result)
                        webView.stopLoading()
                        webView.destroy()
                        dialog.dismiss()
                    }

                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.mediaPlaybackRequiresUserGesture = false
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val requestUrl = request.url.toString()
                            if (mediaUrl.matches(requestUrl) || streamRequest.matches(requestUrl)) {
                                val requestHeaders = request.requestHeaders.toMutableMap()
                                requestHeaders["User-Agent"] = view.settings.userAgentString
                                CookieManager.getInstance().getCookie(requestUrl)
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { requestHeaders["Cookie"] = it }
                                activity.runOnUiThread {
                                    status.text = "Stream found. Sending it to TV…"
                                    finish(ResolvedRequest(requestUrl, requestHeaders))
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    continuation.invokeOnCancellation {
                        activity.runOnUiThread {
                            webView.stopLoading()
                            webView.destroy()
                            dialog.dismiss()
                        }
                    }
                    dialog.setOnCancelListener { finish(null) }
                    dialog.show()
                    dialog.window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    webView.loadUrl(resolverUrl, headers)
                }
            }
        }
    }
}
