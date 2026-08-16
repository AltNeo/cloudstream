package com.lagradost.cloudstream3.remote.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.ActivityCompanionSettingsBinding
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.LanRemoteClient
import com.lagradost.cloudstream3.remote.LanRemoteDiscovery
import com.lagradost.cloudstream3.remote.LanRemoteEndpoint
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.RemoteControlActivity
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.payloadAs
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import kotlinx.coroutines.launch

/**
 * Phone + TV companion settings (plan §10, minimal for actual pairing and operation):
 * active TV card (sync / classic remote / unpair), add-TV discovery + manual host + PIN
 * flow, and the feature toggles. On a TV role the "allow control"/"allow pairing" switches
 * and the paired-phones list are shown instead of the phone-side controls.
 */
class CompanionSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCompanionSettingsBinding
    private lateinit var discovery: LanRemoteDiscovery
    private val discoveredDevices = mutableListOf<LanRemoteEndpoint>()
    private val isTv: Boolean
        get() = PairingManager.isTelevision(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)
        enableEdgeToEdgeCompat()
        binding = ActivityCompanionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDiscovery()
        setupAddTv()
        setupActiveTv()
        setupToggles()
        refreshView()
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    private fun setupDiscovery() {
        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf(getString(R.string.remote_no_devices)),
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.companionDevices.adapter = adapter
        binding.companionDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                discoveredDevices.getOrNull(position)?.let { endpoint ->
                    binding.companionHost.setText(endpoint.host)
                }
            }
        }
        discovery = LanRemoteDiscovery(this) { devices ->
            runOnUiThread {
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)
                adapter.clear()
                adapter.addAll(
                    devices.map { "${it.name} (${it.host})" }
                        .ifEmpty { listOf(getString(R.string.remote_no_devices)) }
                )
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun setupAddTv() {
        binding.companionPair.setOnClickListener {
            val hostPort = endpointFromInput() ?: run {
                binding.companionStatus.setText(R.string.remote_invalid_address)
                return@setOnClickListener
            }
            binding.companionStatus.setText(R.string.companion_pairing_waiting)
            lifecycleScope.launch {
                val tv = PairingFlow.pair(
                    this@CompanionSettingsActivity,
                    hostPort.first,
                    hostPort.second,
                ) { status -> binding.companionStatus.text = status }
                if (tv != null) {
                    binding.companionStatus.text = getString(R.string.companion_paired_with, tv.name)
                    refreshView()
                }
            }
        }
    }

    private fun setupActiveTv() {
        binding.companionSync.setOnClickListener {
            lifecycleScope.launch {
                binding.companionStatus.setText(R.string.companion_syncing)
                if (isTv) {
                    binding.companionStatus.text = getString(R.string.companion_tv_side_hint)
                } else {
                    when (CompanionSessionManager.syncExtensions()) {
                        CompanionSessionManager.ExtensionSyncStatus.SUCCESS ->
                            binding.companionStatus.setText(R.string.companion_synced)
                        CompanionSessionManager.ExtensionSyncStatus.SKIPPED ->
                            binding.companionStatus.setText(R.string.companion_sync_skipped)
                        CompanionSessionManager.ExtensionSyncStatus.FAILED ->
                            binding.companionStatus.setText(R.string.companion_sync_failed)
                    }
                }
            }
        }
        binding.companionClassicRemote.setOnClickListener {
            startActivity(Intent(this, RemoteControlActivity::class.java))
        }
        binding.companionUnpair.setOnClickListener {
            if (isTv) {
                // TV role: this screen manages paired phones (unpair handled per phone below).
                binding.companionStatus.setText(R.string.companion_tv_side_hint)
            } else {
                val active = PairingManager.getActiveTv() ?: return@setOnClickListener
                lifecycleScope.launch {
                    runCatching {
                        LanRemoteClient.send(
                            active,
                            RemoteMessageType.UNPAIR,
                        )
                    }
                    PairingManager.forgetTv(active.deviceId)
                    refreshView()
                    binding.companionStatus.setText(R.string.companion_unpaired)
                }
            }
        }
    }

    private fun setupToggles() {
        binding.companionSyncExt.isChecked = syncExtensionsEnabled()
        binding.companionSyncExt.setOnCheckedChangeListener { _, checked ->
            setSyncExtensionsEnabled(checked)
        }

        binding.companionSyncLib.isChecked = syncLibraryEnabled()
        binding.companionSyncLib.setOnCheckedChangeListener { _, checked ->
            setSyncLibraryEnabled(checked)
        }

        if (isTv) {
            binding.companionAllowControlRow.visibility = View.VISIBLE
            binding.companionAllowPairingRow.visibility = View.VISIBLE
            binding.companionAllowControl.isChecked = PairingManager.isControlAllowed(this)
            binding.companionAllowControl.setOnCheckedChangeListener { _, checked ->
                PairingManager.setControlAllowed(this, checked)
            }
            binding.companionAllowPairing.isChecked = PairingManager.isPairingAllowed(this)
            binding.companionAllowPairing.setOnCheckedChangeListener { _, checked ->
                PairingManager.setPairingAllowed(this, checked)
            }
        } else {
            binding.companionAllowControlRow.visibility = View.GONE
            binding.companionAllowPairingRow.visibility = View.GONE
        }
    }

    private fun refreshView() {
        if (isTv) {
            val phones = PairingManager.getPairedPhones().values.joinToString("\n") { it.name }
            binding.companionActiveTv.text = getString(
                R.string.companion_paired_phones_count,
                PairingManager.getPairedPhones().size,
            )
            binding.companionPhones.text =
                phones.ifEmpty { getString(R.string.companion_no_paired_phones) }
            binding.companionStatus.text = if (PairingManager.isControlAllowed(this)) {
                getString(R.string.companion_receiving_enabled)
            } else {
                getString(R.string.companion_receiving_disabled)
            }
            return
        }
        val active = PairingManager.getActiveTv()
        if (active == null) {
            binding.companionActiveTv.setText(R.string.companion_no_active_tv)
        } else {
            binding.companionActiveTv.text =
                getString(R.string.companion_active_tv_format, active.name, active.host)
        }
        binding.companionPhones.text = ""
    }

    private fun endpointFromInput(): Pair<String, Int>? {
        val input = binding.companionHost.text?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val endpoint = com.lagradost.cloudstream3.remote.parseLanRemoteAddress(input) ?: return null
        if (endpoint.first.isBlank() || endpoint.second !in 1..65535) return null
        return endpoint
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    companion object {
        const val SYNC_EXTENSIONS_KEY = "companion/sync_extensions"
        const val SYNC_LIBRARY_KEY = "companion/sync_library"

        fun syncExtensionsEnabled(): Boolean = getKey<Boolean>(SYNC_EXTENSIONS_KEY) ?: true

        fun setSyncExtensionsEnabled(enabled: Boolean) {
            setKey(SYNC_EXTENSIONS_KEY, enabled)
        }

        fun syncLibraryEnabled(): Boolean = getKey<Boolean>(SYNC_LIBRARY_KEY) ?: true

        fun setSyncLibraryEnabled(enabled: Boolean) {
            setKey(SYNC_LIBRARY_KEY, enabled)
        }
    }
}
