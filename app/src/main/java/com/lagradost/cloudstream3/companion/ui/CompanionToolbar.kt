package com.lagradost.cloudstream3.companion.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.companion.CompanionPreferences
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Installs the small phone-only companion affordance without changing the navigation graph. */
object CompanionToolbar {
    private const val VIEW_TAG = "companion_toolbar_button"

    fun install(root: ViewGroup, owner: LifecycleOwner) {
        if (!isLayout(PHONE) || !CompanionPreferences.isEnabled(root.context)) return
        if (root.findViewWithTag<ImageButton>(VIEW_TAG) != null) return
        val button = ImageButton(root.context).apply {
            tag = VIEW_TAG
            contentDescription = root.context.getString(R.string.connect_to_tv)
            setImageResource(android.R.drawable.ic_media_play)
            imageTintList = ContextCompat.getColorStateList(context, R.color.iconColor)
            setBackgroundResource(android.R.drawable.btn_default)
            setPadding(
                root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size),
                root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size),
                root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size),
                root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size),
            )
            setOnClickListener {
                val activity = root.context as? androidx.fragment.app.FragmentActivity
                if (CompanionUiBridge.playback.value != null && activity != null) {
                    CompanionNowPlayingSheet.show(activity.supportFragmentManager)
                } else {
                    CompanionDevicePicker.show(root.context)
                }
            }
        }
        val params = ViewGroup.LayoutParams(
            root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size) * 2,
            root.resources.getDimensionPixelSize(R.dimen.companion_toolbar_icon_size) * 2,
        )
        if (root is android.widget.FrameLayout) {
            val frameParams = android.widget.FrameLayout.LayoutParams(params).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = root.resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)
                marginEnd = root.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
            }
            root.addView(button, frameParams)
        } else {
            root.addView(button, params)
        }

        owner.lifecycleScope.launch {
            CompanionUiBridge.devices.collectLatest { devices ->
                val hasDevices = devices.any { it.paired || it.address != null }
                button.isVisible = hasDevices
                val connected = devices.firstOrNull { it.connected }
                button.imageTintList = ContextCompat.getColorStateList(
                    root.context,
                    if (connected != null) R.color.colorPrimary else R.color.iconColor,
                )
                button.contentDescription = connected?.let {
                    root.context.getString(R.string.connect_to_tv_connected, it.name)
                } ?: root.context.getString(R.string.connect_to_tv)
            }
        }
    }

    fun uninstall(root: ViewGroup) {
        root.findViewWithTag<ImageButton>(VIEW_TAG)?.let(root::removeView)
    }
}
