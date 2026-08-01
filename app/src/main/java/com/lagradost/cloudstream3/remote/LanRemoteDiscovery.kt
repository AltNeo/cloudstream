package com.lagradost.cloudstream3.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

class LanRemoteDiscovery(
    context: Context,
    private val onDevicesChanged: (List<LanRemoteEndpoint>) -> Unit,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val devices = linkedMapOf<String, LanRemoteEndpoint>()
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != LanRemoteProtocol.SERVICE_TYPE) return
            pendingResolutions.add(serviceInfo)
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            devices.remove(serviceInfo.serviceName)
            publish()
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        multicastLock = wifiManager.createMulticastLock("cloudstream-lan-remote").apply {
            setReferenceCounted(false)
            acquire()
        }
        nsdManager.discoverServices(
            LanRemoteProtocol.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
        pendingResolutions.clear()
        resolving = false
    }

    private fun resolveNext() {
        if (resolving) return
        val service = pendingResolutions.pollFirst() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host?.hostAddress
                if (!host.isNullOrBlank()) {
                    devices[serviceInfo.serviceName] = LanRemoteEndpoint(
                        name = serviceInfo.serviceName,
                        host = host,
                        port = serviceInfo.port,
                    )
                    publish()
                }
                resolving = false
                resolveNext()
            }
        })
    }

    private fun publish() {
        onDevicesChanged(devices.values.sortedBy { it.name })
    }
}
