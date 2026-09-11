package com.lagradost.cloudstream3.ui.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.companion.CompanionPreferences
import com.lagradost.cloudstream3.companion.ui.CompanionUiBridge
import com.lagradost.cloudstream3.companion.ui.CompanionKeyboardController
import com.lagradost.cloudstream3.companion.ui.CompanionToolbar
import com.lagradost.cloudstream3.databinding.FragmentCompanionSettingsBinding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CompanionSettingsFragment : Fragment() {
    private var _binding: FragmentCompanionSettingsBinding? = null
    private val binding get() = _binding!!
    private var approvalShownFor: String? = null
    private var pairingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentCompanionSettingsBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCompanionSettingsBinding.bind(view)
        setUpToolbar(R.string.phone_connection)
        setSystemBarsPadding()

        val context = requireContext()
        binding.companionEnabled.isChecked = CompanionPreferences.isEnabled(context)
        binding.companionDeviceName.setText(CompanionPreferences.deviceName(context))
        binding.companionEnabled.setOnCheckedChangeListener { _, checked ->
            CompanionPreferences.setEnabled(context, checked)
            CompanionUiBridge.setEnabled(checked)
            val activityRoot = activity?.findViewById<ViewGroup>(R.id.homeRoot)
            if (checked && activityRoot != null) {
                CompanionToolbar.install(activityRoot, requireActivity())
                CompanionKeyboardController.install(activityRoot, requireActivity())
            } else if (activityRoot != null) {
                CompanionUiBridge.stopPairing()
                CompanionToolbar.uninstall(activityRoot)
                CompanionKeyboardController.uninstall(activityRoot)
            }
        }
        binding.companionDeviceName.doAfterTextChanged {
            CompanionPreferences.setDeviceName(context, it?.toString().orEmpty())
        }
        binding.companionPair.setOnClickListener { showPairingDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            CompanionUiBridge.devices.collectLatest { devices -> renderDevices(devices) }
        }
    }

    private fun showPairingDialog() {
        pairingJob?.cancel()
        CompanionUiBridge.startPairing()
        val pinInstruction = TextView(requireContext()).apply {
            text = getString(R.string.phone_connection_pairing_pin)
            setTextAppearance(com.lagradost.cloudstream3.R.style.AppTextViewStyle)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.textColor))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.companion_pairing_pin_label_size),
            )
            gravity = Gravity.CENTER
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
            )
        }
        val pin = TextView(requireContext()).apply {
            text = getString(R.string.phone_connection_pairing_waiting)
            setTextAppearance(com.lagradost.cloudstream3.R.style.AppTextViewStyle)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.textColor))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.companion_pairing_pin_size),
            )
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                0,
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
            )
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.phone_connection_pairing_title)
            .setView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(pinInstruction)
                addView(pin)
            })
            .setNegativeButton(android.R.string.cancel) { _, _ -> CompanionUiBridge.stopPairing() }
            .show()
        dialog.setOnDismissListener {
            pairingJob?.cancel()
            pairingJob = null
            CompanionUiBridge.stopPairing()
        }
        pairingJob = viewLifecycleOwner.lifecycleScope.launch {
            CompanionUiBridge.pairing.collectLatest { pairing ->
                val pinText = pairing?.pin
                pin.text = if (pinText.isNullOrBlank()) {
                    getString(R.string.phone_connection_pairing_waiting)
                } else {
                    pinText
                }
                val pendingId = pairing?.pendingDeviceId
                if (!pendingId.isNullOrBlank() && approvalShownFor != pendingId) {
                    approvalShownFor = pendingId
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.phone_connection_pairing_title)
                        .setMessage(
                            pairing.pendingDeviceName
                                ?: getString(R.string.phone_connection_unknown_phone),
                        )
                        .setNegativeButton(R.string.phone_connection_pairing_reject) { _, _ ->
                            CompanionUiBridge.rejectPairing()
                        }
                        .setPositiveButton(R.string.phone_connection_pairing_approve) { _, _ ->
                            CompanionUiBridge.approvePairing()
                        }
                        .show()
                }
                if (pendingId == null) approvalShownFor = null
            }
        }
    }

    private fun renderDevices(devices: List<CompanionUiBridge.Device>) {
        val list = binding.companionPairedDevices
        list.removeAllViews()
        val paired = devices.filter { it.paired }
        if (paired.isEmpty()) {
            list.addView(TextView(requireContext()).apply { setText(R.string.phone_connection_no_paired) })
            return
        }
        paired.forEach { device ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = true
                isFocusableInTouchMode = true
            }
            row.addView(TextView(requireContext()).apply {
                text = device.name
                setTextColor(requireContext().getColor(com.lagradost.cloudstream3.R.color.textColor))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(MaterialButton(requireContext()).apply {
                text = getString(R.string.phone_connection_revoke)
                setOnClickListener { confirmRevoke(device) }
            })
            list.addView(row)
        }
    }

    private fun confirmRevoke(device: CompanionUiBridge.Device) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.phone_connection_revoke)
            .setMessage(R.string.phone_connection_revoke_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.phone_connection_revoke) { _, _ ->
                CompanionUiBridge.revoke(device.id)
            }
            .show()
    }

    override fun onDestroyView() {
        pairingJob?.cancel()
        pairingJob = null
        _binding = null
        super.onDestroyView()
    }
}
