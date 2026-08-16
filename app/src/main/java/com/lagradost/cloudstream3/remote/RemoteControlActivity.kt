package com.lagradost.cloudstream3.remote

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.ActivityRemoteControlBinding
import com.lagradost.cloudstream3.remote.ui.PairingFlow
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import kotlinx.coroutines.launch

/**
 * Classic dpad/text remote (plan §6.7). Kept as a secondary tool behind Companion settings;
 * all sends are now v2 signed envelopes via the active paired TV.
 */
class RemoteControlActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var discovery: LanRemoteDiscovery
    private val discoveredDevices = mutableListOf<LanRemoteEndpoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)
        enableEdgeToEdgeCompat()
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDiscovery()
        setupConnection()
        setupControls()

        // Seed the host field: active TV first, then legacy v1 prefs (plan §5.2).
        val active = PairingManager.getActiveTv()
        val host = active?.host ?: LanRemoteClient.legacyEndpoint(this)?.first
        if (host != null) {
            binding.remoteHost.setText(host)
            binding.remoteStatus.text = getString(R.string.remote_saved_device, host)
        }
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
        binding.remoteDevices.adapter = adapter
        binding.remoteDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                discoveredDevices.getOrNull(position)?.let { endpoint ->
                    binding.remoteHost.setText(endpoint.host)
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

    private fun setupConnection() {
        binding.remoteConnect.setOnClickListener {
            val endpoint = endpointFromInput() ?: run {
                binding.remoteStatus.setText(R.string.remote_invalid_address)
                return@setOnClickListener
            }
            binding.remoteStatus.setText(R.string.remote_connecting)
            lifecycleScope.launch {
                runCatching { LanRemoteClient.ping(endpoint.host, endpoint.port) }
                    .onSuccess { response ->
                        if (response.accepted) {
                            val info = response.payloadAs<DeviceInfo>()
                            val name = info?.name ?: endpoint.host
                            if (info != null && PairingManager.getPairedTvs().containsKey(info.deviceId)) {
                                PairingManager.updateTvEndpoint(info.deviceId, endpoint.host, endpoint.port)
                                binding.remoteStatus.text = getString(R.string.remote_connected_to, name)
                                binding.remotePair.isEnabled = false
                            } else {
                                binding.remoteStatus.text = getString(R.string.companion_pairing_required, name)
                                binding.remotePair.isEnabled = true
                            }
                        } else {
                            binding.remoteStatus.text = response.error
                                ?: getString(R.string.remote_connection_failed)
                        }
                    }
                    .onFailure {
                        binding.remoteStatus.text = getString(R.string.remote_connection_failed)
                    }
            }
        }
        binding.remotePair.setOnClickListener {
            val endpoint = endpointFromInput() ?: return@setOnClickListener
            binding.remoteStatus.setText(R.string.companion_pairing_waiting)
            lifecycleScope.launch {
                val tv = PairingFlow.pair(this@RemoteControlActivity, endpoint.host, endpoint.port) { status ->
                    binding.remoteStatus.text = status
                }
                if (tv != null) {
                    binding.remoteStatus.text = getString(R.string.companion_paired_with, tv.name)
                    binding.remotePair.isEnabled = false
                }
            }
        }
        binding.remoteBrowse.setOnClickListener { finish() }
        binding.remoteLaunch.setOnClickListener {
            send(RemoteMessageType.LAUNCH)
        }
    }

    private fun setupControls() {
        mapOf(
            binding.remoteUp to KeyEvent.KEYCODE_DPAD_UP,
            binding.remoteDown to KeyEvent.KEYCODE_DPAD_DOWN,
            binding.remoteLeft to KeyEvent.KEYCODE_DPAD_LEFT,
            binding.remoteRight to KeyEvent.KEYCODE_DPAD_RIGHT,
            binding.remoteOk to KeyEvent.KEYCODE_DPAD_CENTER,
            binding.remoteBack to KeyEvent.KEYCODE_BACK,
            binding.remotePlayPause to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            binding.remoteRewind to KeyEvent.KEYCODE_MEDIA_REWIND,
            binding.remoteForward to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            binding.remoteVolumeDown to KeyEvent.KEYCODE_VOLUME_DOWN,
            binding.remoteVolumeUp to KeyEvent.KEYCODE_VOLUME_UP,
            binding.remoteMute to KeyEvent.KEYCODE_VOLUME_MUTE,
        ).forEach { (button, keyCode) ->
            button.setOnClickListener {
                send(RemoteMessageType.KEY, KeyPayload(keyCode))
            }
        }

        binding.remoteSendText.setOnClickListener { sendText() }
        binding.remoteText.setOnEditorActionListener { _, _, _ ->
            sendText()
            true
        }
    }

    private fun sendText() {
        val text = binding.remoteText.text?.toString()?.takeIf(String::isNotBlank) ?: return
        send(RemoteMessageType.TEXT, TextPayload(text))
        binding.remoteText.text?.clear()
    }

    private fun send(type: RemoteMessageType, payload: Any? = null) {
        lifecycleScope.launch {
            runCatching { CompanionSessionManager.send(type, payload) }
                .onSuccess { response ->
                    if (!response.accepted) {
                        binding.remoteStatus.text = when (response.error) {
                            "unauthenticated" -> getString(R.string.companion_repair_required)
                            else -> response.error ?: getString(R.string.remote_command_failed)
                        }
                    }
                }
                .onFailure {
                    binding.remoteStatus.text =
                        if (PairingManager.getActiveTv() == null) {
                            getString(R.string.companion_pairing_required, "TV")
                        } else {
                            getString(R.string.remote_connection_failed)
                        }
                }
        }
    }

    private fun endpointFromInput(): LanRemoteEndpoint? {
        val input = binding.remoteHost.text?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val endpoint = parseLanRemoteAddress(input) ?: return null
        if (endpoint.first.isBlank() || endpoint.second !in 1..65535) return null
        return discoveredDevices.firstOrNull { it.host == endpoint.first && it.port == endpoint.second }
            ?: LanRemoteEndpoint(endpoint.first, endpoint.first, endpoint.second)
    }
}
