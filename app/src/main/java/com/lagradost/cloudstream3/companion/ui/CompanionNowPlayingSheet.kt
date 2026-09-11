package com.lagradost.cloudstream3.companion.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Live remote-control surface. All commands are sent through the companion UI bridge. */
class CompanionNowPlayingSheet : BottomSheetDialogFragment() {
    private var inputJob: Job? = null
    private var dragging = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
            )
        }
        val title = TextView(requireContext()).apply {
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.companion_now_playing_title_size),
            )
        }
        val position = TextView(requireContext())
        val scrubber = SeekBar(requireContext())
        root.addView(title)
        root.addView(position)
        root.addView(scrubber)

        val controls = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(commandButton(R.string.remote_previous, "PREV"))
        controls.addView(commandButton(R.string.remote_seek_back, "SEEK_REL", -10_000L))
        controls.addView(commandButton(R.string.remote_play, "TOGGLE"))
        controls.addView(commandButton(R.string.remote_seek_forward, "SEEK_REL", 10_000L))
        controls.addView(commandButton(R.string.remote_next, "NEXT"))
        root.addView(controls)

        val input = EditText(requireContext()).apply {
            hint = getString(R.string.remote_keyboard_hint)
            visibility = View.GONE
            doAfterTextChanged { value ->
                inputJob?.cancel()
                inputJob = lifecycleScope.launch {
                    delay(100)
                    CompanionUiBridge.sendInputText(value?.toString().orEmpty())
                }
            }
        }
        root.addView(input)

        val dpad = MaterialButton(requireContext()).apply {
            text = getString(R.string.remote_keyboard_dpad)
            setOnClickListener { showDpadDialog() }
        }
        root.addView(dpad)

        lifecycleScope.launch {
            CompanionUiBridge.playback.collectLatest { playback ->
                title.text = playback?.title.orEmpty()
                position.text = playback?.let {
                    getString(
                        R.string.remote_position,
                        formatPosition(it.positionMs),
                        formatPosition(it.durationMs),
                    )
                }.orEmpty()
                scrubber.max = playback?.durationMs?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
                if (!dragging) scrubber.progress = playback?.positionMs
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
                input.visibility = if (playback?.inputContext == CompanionUiBridge.InputContext.SEARCH_FIELD) {
                    View.VISIBLE
                } else View.GONE
            }
        }
        scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                CompanionUiBridge.sendPlayerCommand("SEEK_TO", seekBar.progress.toLong())
            }
        })
        return root
    }

    private fun commandButton(text: Int, command: String, positionMs: Long? = null): MaterialButton =
        MaterialButton(requireContext()).apply {
            setText(text)
            setOnClickListener { CompanionUiBridge.sendPlayerCommand(command, positionMs) }
        }

    private fun showDpadDialog() {
        val rows = listOf(
            listOf(android.view.KeyEvent.KEYCODE_DPAD_UP),
            listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
            listOf(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
        )
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { rowKeys ->
            val row = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER }
            rowKeys.forEach { keyCode ->
                row.addView(MaterialButton(requireContext()).apply {
                    setText(when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> R.string.remote_dpad_up
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> R.string.remote_dpad_down
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> R.string.remote_dpad_left
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.remote_dpad_right
                        else -> R.string.remote_dpad_center
                    })
                    setOnClickListener { CompanionUiBridge.sendKey(keyCode) }
                })
            }
            root.addView(row)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remote_keyboard_dpad)
            .setView(root)
            .show()
    }

    private fun formatPosition(value: Long): String {
        val seconds = (value / 1000).coerceAtLeast(0)
        return getString(R.string.remote_position_value, seconds / 60, seconds % 60)
    }

    companion object {
        fun show(manager: FragmentManager) {
            CompanionNowPlayingSheet().show(manager, "companion_now_playing")
        }
    }
}
