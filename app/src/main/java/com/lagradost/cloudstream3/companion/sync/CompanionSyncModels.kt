package com.lagradost.cloudstream3.companion.sync

import com.lagradost.cloudstream3.companion.protocol.SyncField
import com.lagradost.cloudstream3.companion.protocol.SyncRecordPayload
import com.lagradost.cloudstream3.companion.protocol.SyncWatchState
import kotlinx.serialization.Serializable

const val COMPANION_SYNC_MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
const val COMPANION_SYNC_MIN_DURATION_MS = 30_000L

@Serializable
enum class CompanionSyncField {
    VIDEO_POS_DUR,
    VIDEO_WATCH_STATE,
}

@Serializable
enum class CompanionWatchState {
    WATCHED,
}

/** Typed wire record; no arbitrary preference key or value is accepted. */
@Serializable
data class CompanionSyncRecord(
    val accountNamespace: String,
    val field: CompanionSyncField,
    val mediaId: Int,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watchState: CompanionWatchState? = null,
    val updatedAtMs: Long,
)

data class StoredCompanionPosition(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

data class StoredCompanionWatchState(
    val state: CompanionWatchState,
    val updatedAtMs: Long,
)

interface CompanionDataStoreHelperAdapter {
    fun readPosition(accountNamespace: String, mediaId: Int): StoredCompanionPosition?

    fun writePosition(
        accountNamespace: String,
        mediaId: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAtMs: Long,
    )

    fun readWatchState(accountNamespace: String, mediaId: Int): StoredCompanionWatchState?

    fun writeWatchState(
        accountNamespace: String,
        mediaId: Int,
        state: CompanionWatchState,
        updatedAtMs: Long,
    )
}

fun CompanionSyncRecord.storageKey(): String =
    "$accountNamespace/${field.name.lowercase()}/$mediaId"

fun CompanionSyncRecord.toPayload(): SyncRecordPayload = SyncRecordPayload(
    accountNamespace = accountNamespace,
    field = field.toWireField(),
    mediaId = mediaId,
    positionMs = positionMs,
    durationMs = durationMs,
    watchState = watchState?.toWireState(),
    updatedAtMs = updatedAtMs,
)

fun SyncRecordPayload.toSyncRecord(): CompanionSyncRecord = CompanionSyncRecord(
    accountNamespace = accountNamespace,
    field = field.toSyncField(),
    mediaId = mediaId,
    positionMs = positionMs,
    durationMs = durationMs,
    watchState = watchState?.toSyncState(),
    updatedAtMs = updatedAtMs,
)

private fun CompanionSyncField.toWireField(): SyncField = when (this) {
    CompanionSyncField.VIDEO_POS_DUR -> SyncField.VIDEO_POS_DUR
    CompanionSyncField.VIDEO_WATCH_STATE -> SyncField.VIDEO_WATCH_STATE
}

private fun SyncField.toSyncField(): CompanionSyncField = when (this) {
    SyncField.VIDEO_POS_DUR -> CompanionSyncField.VIDEO_POS_DUR
    SyncField.VIDEO_WATCH_STATE -> CompanionSyncField.VIDEO_WATCH_STATE
}

private fun CompanionWatchState.toWireState(): SyncWatchState = SyncWatchState.WATCHED

private fun SyncWatchState.toSyncState(): CompanionWatchState = CompanionWatchState.WATCHED
