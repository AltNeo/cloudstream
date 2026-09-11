package com.lagradost.cloudstream3.companion.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.companion.CompanionPreferences
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shows the remote search input above the phone navigation bar while TV search has focus. */
object CompanionKeyboardController {
    fun install(root: ViewGroup, owner: LifecycleOwner) {
        if (!isLayout(PHONE) || !CompanionPreferences.isEnabled(root.context)) return
        if (root.findViewWithTag<EditText>(VIEW_TAG) != null) return
        val input = EditText(root.context).apply {
            tag = VIEW_TAG
            hint = root.context.getString(R.string.remote_keyboard_hint)
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(root.context, R.color.companion_keyboard_text))
            setHintTextColor(ContextCompat.getColor(root.context, R.color.companion_keyboard_hint))
            isVisible = false
        }
        if (root is android.widget.FrameLayout) {
            root.addView(input, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = root.resources.getDimensionPixelSize(R.dimen.nav_view_height)
            })
        } else root.addView(input)

        var pending: Job? = null
        input.setOnEditorActionListener { _, _, _ -> false }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pending?.cancel()
                pending = owner.lifecycleScope.launch {
                    delay(100)
                    CompanionUiBridge.sendInputText(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        owner.lifecycleScope.launch {
            CompanionUiBridge.playback.collectLatest { playback ->
                input.isVisible = playback?.inputContext == CompanionUiBridge.InputContext.SEARCH_FIELD
                if (!input.isVisible) input.text = null
            }
        }
    }

    fun uninstall(root: ViewGroup) {
        root.findViewWithTag<EditText>(VIEW_TAG)?.let(root::removeView)
    }

    private const val VIEW_TAG = "companion_keyboard_input"
}
