package com.lagradost.cloudstream3.companion.runtime

import com.lagradost.cloudstream3.companion.crypto.AeadRecordLayer
import com.lagradost.cloudstream3.companion.crypto.SecureChannelState
import com.lagradost.cloudstream3.companion.phone.PhoneWireConnection
import com.lagradost.cloudstream3.companion.transport.CompanionConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adds the record layer to the raw framed socket supplied by the transport. */
class CompanionSecureWire(
    private val connection: CompanionConnection,
    recordLayer: AeadRecordLayer,
) : PhoneWireConnection {
    private val channel = SecureChannelState().also { it.establish(recordLayer) }

    override suspend fun readFrame(): ByteArray = withContext(Dispatchers.IO) {
        channel.decryptApplicationRecord(connection.readFrame())
    }

    override suspend fun writeFrame(payload: ByteArray) = withContext(Dispatchers.IO) {
        connection.writeFrame(channel.encryptApplicationRecord(payload))
    }

    override fun close() {
        channel.close()
        connection.close()
    }
}
