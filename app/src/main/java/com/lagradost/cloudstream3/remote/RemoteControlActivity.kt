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
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import kotlinx.coroutines.launch

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

        LanRemoteClient.selectedEndpoint(this)?.let { endpoint ->
            binding.remoteHost.setText(endpoint.host)
            binding.remoteStatus.text = getString(R.string.remote_saved_device, endpoint.host)
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
                runCatching { LanRemoteClient.ping(endpoint) }
                    .onSuccess { response ->
                        if (response.accepted) {
                            LanRemoteClient.selectEndpoint(this@RemoteControlActivity, endpoint)
                            binding.remoteStatus.text = getString(
                                R.string.remote_connected_to,
                                response.message ?: endpoint.host,
                            )
                        } else {
                            binding.remoteStatus.text = response.message
                                ?: getString(R.string.remote_connection_failed)
                        }
                    }
                    .onFailure {
                        binding.remoteStatus.setText(R.string.remote_connection_failed)
                    }
            }
        }
        binding.remoteBrowse.setOnClickListener { finish() }
        binding.remoteLaunch.setOnClickListener {
            send(LanRemoteRequest(command = LanRemoteCommand.LAUNCH))
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
                send(LanRemoteRequest(command = LanRemoteCommand.KEY, keyCode = keyCode))
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
        send(LanRemoteRequest(command = LanRemoteCommand.TEXT, text = text))
        binding.remoteText.text?.clear()
    }

    private fun send(request: LanRemoteRequest) {
        lifecycleScope.launch {
            runCatching { LanRemoteClient.send(this@RemoteControlActivity, request) }
                .onSuccess { response ->
                    if (!response.accepted) {
                        binding.remoteStatus.text = response.message
                            ?: getString(R.string.remote_command_failed)
                    }
                }
                .onFailure {
                    binding.remoteStatus.setText(R.string.remote_connection_failed)
                }
        }
    }

    private fun endpointFromInput(): LanRemoteEndpoint? {
        val input = binding.remoteHost.text?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val host = input.substringBeforeLast(":", input).trim()
        val port = if (host == input) {
            LanRemoteProtocol.PORT
        } else {
            input.substringAfterLast(":").toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535) return null
        return discoveredDevices.firstOrNull { it.host == host && it.port == port }
            ?: LanRemoteEndpoint(host, host, port)
    }
}
