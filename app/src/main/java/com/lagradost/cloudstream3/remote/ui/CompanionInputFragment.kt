package com.lagradost.cloudstream3.remote.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.SheetCompanionInputBinding
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.InputContextPayload
import com.lagradost.cloudstream3.remote.shouldSendInputText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone-side reactive input sheet (plan F1 / checkpoint 3): opened from the compact input bar
 * in MainActivity while the TV reports an active SEARCH_FIELD. It starts from the TV's echoed
 * currentText and sends debounced (~75 ms) whole-string INPUT_TEXT replacements as the user
 * types. Unicode-safe: the string is never sliced, and the whole-string bound plus the
 * send/echo feedback-loop guard are enforced by [CompanionSessionManager.sendInputText] and the
 * shared [shouldSendInputText] policy. Capability-gated: the sheet refuses to open when the TV
 * did not advertise [DeviceInfo.CAP_INPUT_TEXT]. Lock-screen RemoteInput is deliberately out of
 * scope (checkpoint 3).
 */
class CompanionInputFragment : BottomSheetDialogFragment() {
    private var _binding: SheetCompanionInputBinding? = null
    private val binding get() = _binding!!

    private var debounceJob: Job? = null
    private var finalTextQueued = false

    /** Debounce window for whole-string sends (plan F1: ~50-100 ms). */
    private companion object {
        const val DEBOUNCE_MS = 75L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetCompanionInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Capability gate: without CAP_INPUT_TEXT the TV cannot apply whole-string input, so
        // the sheet has nothing useful to do (the bar already hides in that case, this guards
        // direct opens).
        if (!CompanionSessionManager.state.value.canSendInputText) {
            dismiss()
            return
        }
        // Start from the TV's echoed current text, cursor at the end.
        val current = CompanionSessionManager.state.value.inputContext?.currentText.orEmpty()
        binding.companionInputEdit.setText(current)
        binding.companionInputEdit.setSelection(binding.companionInputEdit.text.length)
        binding.companionInputEdit.requestFocus()
        binding.companionInputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                flushAndDismiss()
                true
            } else {
                false
            }
        }
        binding.companionInputClose.setOnClickListener { flushAndDismiss() }

        // Debounced whole-string send on every change (plan F1).
        binding.companionInputEdit.doAfterTextChanged { editable ->
            val text = editable?.toString() ?: return@doAfterTextChanged
            debounceJob?.cancel()
            debounceJob = lifecycleScope.launch {
                delay(DEBOUNCE_MS)
                CompanionSessionManager.sendInputText(text)
            }
        }

        // Mirror TV-side echoes (e.g. the TV user typing) back into the field, but never while
        // the phone field is focused: the phone field is authoritative while the user types,
        // and applying an echo mid-composition would fight the IME.
        lifecycleScope.launch {
            CompanionSessionManager.inputContext.collect { payload ->
                if (_binding == null) return@collect
                if (payload == null || payload.context == InputContextPayload.Context.IDLE) {
                    // The TV no longer has a focused editable field (focus lost or disconnected).
                    flushAndDismiss()
                    return@collect
                }
                val tvText = payload.currentText ?: return@collect
                val edit = binding.companionInputEdit
                if (!edit.hasFocus() && edit.text.toString() != tvText) {
                    edit.setText(tvText)
                    edit.setSelection(edit.text.length)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pop the keyboard as soon as the sheet appears.
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    /** Sends the current text immediately (no debounce) and closes the sheet. */
    private fun flushAndDismiss() {
        queueFinalText()
        dismiss()
    }

    private fun queueFinalText() {
        if (finalTextQueued) return
        debounceJob?.cancel()
        debounceJob = null
        val text = _binding?.companionInputEdit?.text?.toString() ?: return
        val echoed = CompanionSessionManager.state.value.inputContext?.currentText
        if (shouldSendInputText(text, echoed)) {
            CompanionSessionManager.enqueueInputText(text)
        }
        finalTextQueued = true
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Back, outside-tap and swipe dismissals must not cancel the final debounced value.
        queueFinalText()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        debounceJob?.cancel()
        debounceJob = null
        _binding = null
        super.onDestroyView()
    }
}
