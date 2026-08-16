package com.lagradost.cloudstream3.remote.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.remote.LanRemoteClient
import com.lagradost.cloudstream3.remote.LanRemoteProtocol
import com.lagradost.cloudstream3.remote.LibraryEntry
import com.lagradost.cloudstream3.remote.LibrarySyncPayload
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.PairedTv
import com.lagradost.cloudstream3.remote.RemoteEvent
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.payloadAs
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import com.lagradost.cloudstream3.remote.ui.CompanionSettingsActivity
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.RESULT_DUB
import com.lagradost.cloudstream3.utils.RESULT_EPISODE
import com.lagradost.cloudstream3.utils.RESULT_FAVORITES_STATE_DATA
import com.lagradost.cloudstream3.utils.RESULT_RESUME_WATCHING
import com.lagradost.cloudstream3.utils.RESULT_SEASON
import com.lagradost.cloudstream3.utils.RESULT_SUBSCRIBED_STATE_DATA
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE_DATA
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import com.lagradost.cloudstream3.utils.VIDEO_WATCH_STATE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Library sync (plan §9): bidirectional, last-write-wins per key, current account only,
 * phone-authoritative for list membership.
 *
 * Keys travel *unprefixed* on the wire ("$KEY/$id") and each side re-prefixes with its own
 * [DataStoreHelper.currentAccount] on apply. Applying uses raw `putString` into
 * [com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs] — the values are already JSON
 * literals (DataStore.setKey writes `value.toJsonLiteral()`), so going through setKey would
 * double-encode (scout-sync blocker #3). [DataStoreHelper.isApplyingRemote] is set for the
 * duration of an apply as a belt-and-braces echo guard for any setter that runs during it.
 */
object LibrarySyncManager {
    // Timestamp side map: "$currentAccount/companion/lib_sync_ts/<unprefixedKey>" -> ms
    const val SYNC_TS_KEY = "companion/lib_sync_ts"

    private const val TAG = "LibrarySyncManager"
    /** Full-dump payloads larger than this are sent as several bounded pages instead. */
    private const val MAX_SAFE_PAYLOAD_BYTES = 700 * 1024
    private const val PAGE_BUDGET_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 2_048
    private const val MAX_KEY_BYTES = 256
    private const val MAX_VALUE_BYTES = 256 * 1024

    private val syncGroups = listOf(
        RESULT_WATCH_STATE_DATA,
        RESULT_FAVORITES_STATE_DATA,
        RESULT_SUBSCRIBED_STATE_DATA,
        RESULT_WATCH_STATE,
        RESULT_RESUME_WATCHING,
        VIDEO_POS_DUR,
        VIDEO_WATCH_STATE,
        RESULT_EPISODE,
        RESULT_SEASON,
        RESULT_DUB,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingKeys = linkedSetOf<String>()
    private var debounceJob: Job? = null

    // ------------------------------------------------------------------
    // Capture (fired from DataStoreHelper.markSyncedWrite)
    // ------------------------------------------------------------------

    fun onLibraryChanged(context: Context, unprefixedKey: String) {
        if (PairingManager.isTelevision(context)) {
            scheduleTvPush(context, unprefixedKey)
        } else {
            schedulePhonePush(context, unprefixedKey)
        }
    }

    private fun scheduleTvPush(context: Context, unprefixedKey: String) {
        if (!CompanionSettingsActivity.syncLibraryEnabled()) return
        synchronized(pendingKeys) { pendingKeys.add(unprefixedKey) }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2_000)
            val keys = synchronized(pendingKeys) { pendingKeys.toSet().also { pendingKeys.clear() } }
            if (keys.isEmpty()) return@launch
            val delta = dump(context, keys)
            NowPlayingHub.broadcast(RemoteEvent(kind = RemoteEvent.Kind.LIBRARY_DELTA, libraryDelta = delta))
        }
    }

    private fun schedulePhonePush(context: Context, unprefixedKey: String) {
        if (!CompanionSettingsActivity.syncLibraryEnabled()) return
        synchronized(pendingKeys) { pendingKeys.add(unprefixedKey) }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2_000)
            val keys = synchronized(pendingKeys) { pendingKeys.toSet().also { pendingKeys.clear() } }
            if (keys.isEmpty()) return@launch
            if (PairingManager.getActiveTv() == null) return@launch
            val delta = dump(context, keys)
            runCatching {
                LanRemoteClient.sendActive(RemoteMessageType.SYNC_LIBRARY, delta)
            }.onFailure {
                Log.w(TAG, "Library delta push failed: ${it.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Dump / apply
    // ------------------------------------------------------------------

    fun dump(context: Context, changedKeys: Set<String>? = null): LibrarySyncPayload {
        val prefs = context.getSharedPrefs()
        val account = DataStoreHelper.currentAccount
        val entries = mutableListOf<LibraryEntry>()

        if (changedKeys != null) {
            changedKeys.filter(::isAllowedLibraryKey).forEach { unprefixed ->
                val prefixed = "$account/$unprefixed"
                entries.add(
                    LibraryEntry(unprefixed, prefs.getString(prefixed, null), getSyncTs(context, unprefixed))
                )
            }
            return LibrarySyncPayload(full = false, entries = entries)
        }

        syncGroups.forEach { group ->
            val folder = "$account/$group"
            context.getKeys(folder).forEach { prefixed ->
                val unprefixed = prefixed.removePrefix("$account/")
                if (!isAllowedLibraryKey(unprefixed)) return@forEach
                entries.add(
                    LibraryEntry(unprefixed, prefs.getString(prefixed, null), getSyncTs(context, unprefixed))
                )
            }
        }
        return LibrarySyncPayload(full = true, entries = entries)
    }

    /**
     * Pure LWW/bootstrap decision for one entry (unit-tested). Entries written before this
     * feature shipped carry ts 0 on both devices; when both sides are unknown (0/0) the
     * entry is applied only if the local value is absent, and the apply stamps a real
     * timestamp so the tie is broken on the next sync (review R1).
     */
    internal fun shouldApplyRemoteEntry(
        remoteUpdatedAtMs: Long,
        remoteValueJson: String?,
        localTs: Long,
        localValue: String?,
    ): Boolean {
        val isBootstrap = remoteUpdatedAtMs == 0L && localTs == 0L
        if (isBootstrap) return remoteValueJson != null && localValue == null
        return remoteUpdatedAtMs > localTs
    }

    /**
     * Applies a remote payload with per-key LWW using the timestamp side map.
     * Returns this device's own dump so the peer can apply it in the same round trip.
     */
    fun apply(context: Context, payload: LibrarySyncPayload): LibrarySyncPayload {
        if (!CompanionSettingsActivity.syncLibraryEnabled()) return dump(context)
        if (!validatePayload(payload)) return dump(context)
        val prefs = context.getSharedPrefs()
        val account = DataStoreHelper.currentAccount
        var changed = 0
        val editor = prefs.edit()
        DataStoreHelper.isApplyingRemote = true
        try {
            payload.entries.forEach { entry ->
                // validatePayload checked this before the editor was created; keep the local
                // guard next to the write so future callers cannot bypass the allowlist.
                if (!isAllowedEntry(entry)) return@forEach
                val prefixed = "$account/${entry.key}"
                if (!shouldApplyRemoteEntry(
                        entry.updatedAtMs,
                        entry.valueJson,
                        getSyncTs(context, entry.key),
                        prefs.getString(prefixed, null),
                    )
                ) {
                    return@forEach
                }
                if (entry.valueJson == null) {
                    editor.remove(prefixed)
                } else {
                    editor.putString(prefixed, entry.valueJson)
                }
                editor.putString(
                    "$account/$SYNC_TS_KEY/${entry.key}",
                    (if (entry.updatedAtMs == 0L) System.currentTimeMillis() else entry.updatedAtMs).toString(),
                )
                changed++
            }
            editor.apply()
        } finally {
            DataStoreHelper.isApplyingRemote = false
        }
        if (changed > 0) {
            MainActivity.bookmarksUpdatedEvent(true)
            MainActivity.reloadLibraryEvent(true)
        }
        return dump(context)
    }

    /**
     * Phone: full exchange over one-shot SYNC_LIBRARY; returns the TV's dump after applying
     * it locally. Oversized dumps are sent as several bounded pages (each gets a bounded
     * reply over the same keys) so a large library cannot silently exceed the 1 MB frame
     * cap (review R5). Failures are logged, never silently swallowed.
     */
    suspend fun fullSyncPhone(context: Context): Boolean {
        val tv = PairingManager.getActiveTv() ?: return false
        return fullSyncPhone(context, tv)
    }

    /** Full exchange pinned to [tv], used by a connection epoch that must not retarget. */
    suspend fun fullSyncPhone(context: Context, tv: PairedTv): Boolean {
        return fullSyncPhone(context, tv) { true }
    }

    suspend fun fullSyncPhone(
        context: Context,
        tv: PairedTv,
        stillCurrent: () -> Boolean,
    ): Boolean {
        if (!CompanionSettingsActivity.syncLibraryEnabled()) return true
        val local = dump(context)
        val pages = if (payloadFits(local)) {
            listOf(local)
        } else {
            chunkEntries(local.entries, PAGE_BUDGET_BYTES)
        }
        for (page in pages) {
            if (!stillCurrent()) return false
            val reply = runCatching {
                LanRemoteClient.send(tv, RemoteMessageType.SYNC_LIBRARY, page)
            }.getOrElse {
                Log.w(TAG, "Full library sync failed: ${it.message}")
                return false
            }
            if (!reply.accepted) {
                Log.w(TAG, "Full library sync rejected: ${reply.error}")
                return false
            }
            if (!stillCurrent()) return false
            val replyPayload = reply.payloadAs<LibrarySyncPayload>()
            if (replyPayload == null) {
                Log.w(TAG, "Full library sync reply missing payload")
                return false
            }
            if (!stillCurrent()) return false
            apply(context, replyPayload)
        }
        return true
    }

    /** TV: apply a SYNC_LIBRARY request and build the reply payload (used by the server). */
    fun handleSyncLibrary(context: Context, payload: LibrarySyncPayload): LibrarySyncPayload {
        // A TV with "Sync library" off neither applies remote data nor shares its own.
        if (!CompanionSettingsActivity.syncLibraryEnabled() || !validatePayload(payload)) {
            return LibrarySyncPayload(full = false)
        }
        apply(context, payload)
        // Keep the reply bounded: a delta request gets a delta reply over the same keys, so
        // neither direction can exceed the frame cap on very large libraries.
        return if (payload.full) {
            dump(context)
        } else {
            dump(context, payload.entries.map { it.key }.toSet())
        }
    }

    fun getSyncTs(context: Context, unprefixedKey: String): Long {
        val prefs = context.getSharedPrefs()
        return prefs.getString("${DataStoreHelper.currentAccount}/$SYNC_TS_KEY/$unprefixedKey", null)
            ?.toLongOrNull() ?: 0L
    }

    /**
     * Deterministic hash over the local library (key@value@ts). Advertised in HELLO so the
     * phone can skip a redundant full sync on reconnect when both sets already match
     * (review runtime finding 2).
     */
    fun librarySetHash(context: Context): String? {
        val entries = dump(context).entries
        if (entries.isEmpty()) return null
        val canonical = entries
            .map { "${it.key}@${it.valueJson ?: ""}@${it.updatedAtMs}" }
            .sorted()
            .joinToString("\n")
        return sha256Hex(canonical)
    }

    /** Validates the untrusted library data before it is used as a preference path/value. */
    internal fun validatePayload(payload: LibrarySyncPayload, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (payload.entries.size > MAX_ENTRIES) return false
        val encodedSize = runCatching {
            LanRemoteProtocol.json.encodeToString(LibrarySyncPayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8).size
        }.getOrNull() ?: return false
        if (encodedSize > MAX_SAFE_PAYLOAD_BYTES) return false
        return payload.entries.all { entry ->
            isAllowedEntry(entry) &&
                (entry.updatedAtMs == 0L || entry.updatedAtMs >= 0L &&
                    entry.updatedAtMs <= nowMs + com.lagradost.cloudstream3.remote.RemoteAuth.MAX_CLOCK_SKEW_MS)
        }
    }

    internal fun isAllowedLibraryKey(key: String): Boolean {
        val separator = key.indexOf('/')
        if (separator <= 0 || separator == key.lastIndex) return false
        val group = key.substring(0, separator)
        val id = key.substring(separator + 1)
        return group in syncGroups && id.matches(Regex("[0-9]+")) &&
            key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES
    }

    private fun isAllowedEntry(entry: LibraryEntry): Boolean {
        if (!isAllowedLibraryKey(entry.key)) return false
        val value = entry.valueJson ?: return true
        if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) return false
        val element = runCatching { LanRemoteProtocol.json.parseToJsonElement(value) }.getOrNull()
            ?: return false
        return validValueShape(entry.key.substringBefore('/'), element)
    }

    // Stored library values are either scalar enum/status values or JSON objects. Keep this
    // deliberately structural: the app's generated serializers evolve, but preference paths
    // must never escape these documented shapes.
    private fun validValueShape(group: String, element: JsonElement): Boolean = when {
        group == VIDEO_POS_DUR -> element is JsonObject &&
            (element["position"] as? JsonPrimitive)?.content?.toLongOrNull() != null &&
            (element["duration"] as? JsonPrimitive)?.content?.toLongOrNull() != null
        group == RESULT_RESUME_WATCHING -> element is JsonObject &&
            (element["parentId"] as? JsonPrimitive)?.content?.toIntOrNull() != null &&
            (element["updateTime"] as? JsonPrimitive)?.content?.toLongOrNull() != null &&
            (element["isFromDownload"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() != null
        group == RESULT_WATCH_STATE_DATA || group == RESULT_FAVORITES_STATE_DATA ||
            group == RESULT_SUBSCRIBED_STATE_DATA -> element is JsonObject &&
            setOf("id", "name", "url", "apiName").all { it in element }
        group == VIDEO_WATCH_STATE -> element is JsonPrimitive &&
            element.isString && element.content == "Watched"
        else -> element is JsonPrimitive && element.content.toIntOrNull() != null
    }

    /** Whether the serialized payload fits in one frame with headroom for the envelope. */
    private fun payloadFits(payload: LibrarySyncPayload): Boolean =
        validatePayload(payload)

    /** Splits a full dump into key-sorted pages, each within [budgetBytes] of serialized payload. */
    internal fun chunkEntries(entries: List<LibraryEntry>, budgetBytes: Int): List<LibrarySyncPayload> {
        if (entries.size <= 1) return listOf(LibrarySyncPayload(full = false, entries = entries))
        val json = LanRemoteProtocol.json
        val sorted = entries.sortedBy { it.key }
        val pages = mutableListOf<LibrarySyncPayload>()
        var page = mutableListOf<LibraryEntry>()
        var size = 0
        for (entry in sorted) {
            val entrySize = json.encodeToString(LibraryEntry.serializer(), entry).length
            if (page.isNotEmpty() && size + entrySize > budgetBytes) {
                pages.add(LibrarySyncPayload(full = false, entries = page))
                page = mutableListOf()
                size = 0
            }
            page.add(entry)
            size += entrySize
        }
        if (page.isNotEmpty()) pages.add(LibrarySyncPayload(full = false, entries = page))
        return pages
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
