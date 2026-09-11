package com.lagradost.cloudstream3.companion.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CompanionServiceRecord(
    val serviceName: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val deviceName: String,
    val fingerprint: String,
)

object CompanionNsdRecords {
    const val SERVICE_TYPE = "_cloudstream-companion._tcp."
    const val TXT_VERSION = "v"
    const val TXT_NAME = "name"
    const val TXT_FINGERPRINT = "fp"

    fun attributes(deviceName: String, fingerprint: String): Map<String, ByteArray> = mapOf(
        TXT_VERSION to "3".encodeToByteArray(),
        TXT_NAME to deviceName.encodeToByteArray(),
        TXT_FINGERPRINT to fingerprint.encodeToByteArray(),
    )

    fun fromServiceInfo(info: NsdServiceInfo): CompanionServiceRecord? {
        val attributes = info.attributes
        val version = attributes[TXT_VERSION]?.decodeToString()?.toIntOrNull() ?: return null
        val name = attributes[TXT_NAME]?.decodeToString() ?: return null
        val fingerprint = attributes[TXT_FINGERPRINT]?.decodeToString() ?: return null
        val host = hostAddress(info) ?: return null
        val port = info.port
        if (version != 3 || port !in 1..65535) return null
        return CompanionServiceRecord(
            serviceName = info.serviceName,
            host = host,
            port = port,
            protocolVersion = version,
            deviceName = name,
            fingerprint = fingerprint,
        )
    }

    private fun hostAddress(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.firstOrNull()?.hostAddress
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }
}

class CompanionNsdManager(
    context: Context,
    private val onServiceChanged: (List<CompanionServiceRecord>) -> Unit,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.d(TAG, message) else Log.w(TAG, message, error)
    },
) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrationGeneration = AtomicLong(0)
    private val discoveryGeneration = AtomicLong(0)
    private val records = ConcurrentHashMap<String, CompanionServiceRecord>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registered = false
    private var discovering = false

    @Synchronized
    fun register(serviceName: String, port: Int, deviceName: String, fingerprint: String) {
        require(port in 1..65535) { "port must be between 1 and 65535" }
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = CompanionNsdRecords.SERVICE_TYPE
            this.port = port
            CompanionNsdRecords.attributes(deviceName, fingerprint).forEach { (key, value) ->
                setAttribute(key, value.decodeToString())
            }
        }
        val listener = RegistrationListener(registrationGeneration.incrementAndGet())
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun startDiscovery() {
        val currentGeneration = discoveryGeneration.incrementAndGet()
        records.clear()
        notifyRecords()
        val listener = DiscoveryListener(currentGeneration)
        discoveryListener = listener
        nsdManager.discoverServices(
            CompanionNsdRecords.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    @Synchronized
    fun stop() {
        registrationGeneration.incrementAndGet()
        discoveryGeneration.incrementAndGet()
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        registrationListener = null
        discoveryListener = null
        registered = false
        discovering = false
        records.clear()
        notifyRecords()
    }

    private fun isCurrentRegistration(value: Long): Boolean = registrationGeneration.get() == value

    private fun isCurrentDiscovery(value: Long): Boolean = discoveryGeneration.get() == value

    private fun notifyRecords() = onServiceChanged(records.values.sortedBy { it.deviceName })

    private inner class RegistrationListener(private val listenerGeneration: Long) :
        NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            if (isCurrentRegistration(listenerGeneration)) registered = true
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (isCurrentRegistration(listenerGeneration)) {
                registered = false
                logger("companion NSD registration failed: $errorCode", null)
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            if (isCurrentRegistration(listenerGeneration)) registered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (isCurrentRegistration(listenerGeneration)) logger("companion NSD unregister failed: $errorCode", null)
        }
    }

    private inner class DiscoveryListener(private val listenerGeneration: Long) :
        NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (isCurrentDiscovery(listenerGeneration)) discovering = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            if (isCurrentDiscovery(listenerGeneration)) discovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            if (isCurrentDiscovery(listenerGeneration)) {
                discovering = false
                logger("companion NSD discovery failed: $errorCode", null)
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            if (isCurrentDiscovery(listenerGeneration)) logger("companion NSD stop failed: $errorCode", null)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!isCurrentDiscovery(listenerGeneration)) return
            runCatching {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, ResolveListener(listenerGeneration))
            }.onFailure { logger("companion NSD resolve failed", it) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (!isCurrentDiscovery(listenerGeneration)) return
            records.remove(serviceInfo.serviceName)
            notifyRecords()
        }
    }

    private inner class ResolveListener(private val listenerGeneration: Long) :
        NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            if (!isCurrentDiscovery(listenerGeneration)) return
            CompanionNsdRecords.fromServiceInfo(serviceInfo)?.let { record ->
                records[record.serviceName] = record
                notifyRecords()
            }
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (isCurrentDiscovery(listenerGeneration)) logger("companion NSD resolve failed: $errorCode", null)
        }
    }

    companion object {
        private const val TAG = "CompanionNsd"
    }
}
