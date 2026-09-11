package com.lagradost.cloudstream3.companion.sync

import android.content.Context
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import com.lagradost.cloudstream3.utils.VIDEO_WATCH_STATE

/**
 * Bridges the typed companion records to the existing account-scoped DataStoreHelper keys.
 * Timestamps are kept in a companion-owned metadata folder because the legacy position and
 * watch-state values have no timestamp field.
 */
class AndroidCompanionDataStoreHelperAdapter(
    context: Context,
) : CompanionDataStoreHelperAdapter {
    private val appContext = context.applicationContext

    override fun readPosition(
        accountNamespace: String,
        mediaId: Int,
    ): StoredCompanionPosition? {
        if (!isCurrentAccount(accountNamespace)) return null
        val value = appContext.getKey<DataStoreHelper.PosDur>(
            "$accountNamespace/$VIDEO_POS_DUR",
            mediaId.toString(),
        ) ?: return null
        val timestamp = readTimestamp(accountNamespace, CompanionSyncField.VIDEO_POS_DUR, mediaId)
            ?: return null
        return StoredCompanionPosition(value.position, value.duration, timestamp)
    }

    override fun writePosition(
        accountNamespace: String,
        mediaId: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAtMs: Long,
    ) {
        if (!isCurrentAccount(accountNamespace)) return
        appContext.setKey(
            "$accountNamespace/$VIDEO_POS_DUR",
            mediaId.toString(),
            DataStoreHelper.PosDur(positionMs, durationMs),
        )
        writeTimestamp(accountNamespace, CompanionSyncField.VIDEO_POS_DUR, mediaId, updatedAtMs)
    }

    override fun readWatchState(
        accountNamespace: String,
        mediaId: Int,
    ): StoredCompanionWatchState? {
        if (!isCurrentAccount(accountNamespace)) return null
        val state = appContext.getKey<VideoWatchState>(
            "$accountNamespace/$VIDEO_WATCH_STATE",
            mediaId.toString(),
        ) ?: return null
        if (state != VideoWatchState.Watched) return null
        val timestamp = readTimestamp(
            accountNamespace,
            CompanionSyncField.VIDEO_WATCH_STATE,
            mediaId,
        ) ?: return null
        return StoredCompanionWatchState(CompanionWatchState.WATCHED, timestamp)
    }

    override fun writeWatchState(
        accountNamespace: String,
        mediaId: Int,
        state: CompanionWatchState,
        updatedAtMs: Long,
    ) {
        if (!isCurrentAccount(accountNamespace) || state != CompanionWatchState.WATCHED) return
        appContext.setKey(
            "$accountNamespace/$VIDEO_WATCH_STATE",
            mediaId.toString(),
            VideoWatchState.Watched,
        )
        writeTimestamp(accountNamespace, CompanionSyncField.VIDEO_WATCH_STATE, mediaId, updatedAtMs)
    }

    private fun isCurrentAccount(accountNamespace: String): Boolean =
        accountNamespace == DataStoreHelper.currentAccount

    private fun readTimestamp(
        accountNamespace: String,
        field: CompanionSyncField,
        mediaId: Int,
    ): Long? = appContext.getKey<CompanionSyncTimestamp>(
        metadataFolder(accountNamespace, field),
        mediaId.toString(),
    )?.updatedAtMs

    private fun writeTimestamp(
        accountNamespace: String,
        field: CompanionSyncField,
        mediaId: Int,
        updatedAtMs: Long,
    ) {
        appContext.setKey(
            metadataFolder(accountNamespace, field),
            mediaId.toString(),
            CompanionSyncTimestamp(updatedAtMs),
        )
    }

    private fun metadataFolder(accountNamespace: String, field: CompanionSyncField): String =
        "$accountNamespace/$METADATA_FOLDER/${field.name.lowercase()}"

    private data class CompanionSyncTimestamp(val updatedAtMs: Long)

    private companion object {
        const val METADATA_FOLDER = "companion_sync_timestamps"
    }
}
