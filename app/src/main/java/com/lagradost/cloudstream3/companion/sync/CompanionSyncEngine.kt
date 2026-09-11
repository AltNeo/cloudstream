package com.lagradost.cloudstream3.companion.sync

sealed class CompanionSyncApplyResult {
    data object Applied : CompanionSyncApplyResult()
    data object IgnoredStale : CompanionSyncApplyResult()
    data class Rejected(val reason: CompanionSyncRejectReason) : CompanionSyncApplyResult()
}

enum class CompanionSyncRejectReason {
    ACCOUNT_MISMATCH,
    INVALID_ACCOUNT_NAMESPACE,
    INVALID_MEDIA_ID,
    INVALID_TIMESTAMP,
    FUTURE_TIMESTAMP,
    INVALID_POSITION,
    INVALID_WATCH_STATE,
}

fun interface CompanionSyncClock {
    fun nowMs(): Long
}

object SystemCompanionSyncClock : CompanionSyncClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class CompanionSyncEngine(
    private val adapter: CompanionDataStoreHelperAdapter,
    private val clock: CompanionSyncClock = SystemCompanionSyncClock,
) {
    private val lock = Any()

    fun apply(
        currentAccountNamespace: String,
        record: CompanionSyncRecord,
    ): CompanionSyncApplyResult = synchronized(lock) {
        validate(currentAccountNamespace, record)?.let {
            return@synchronized CompanionSyncApplyResult.Rejected(it)
        }

        val storedTimestamp = when (record.field) {
            CompanionSyncField.VIDEO_POS_DUR ->
                adapter.readPosition(record.accountNamespace, record.mediaId)?.updatedAtMs
            CompanionSyncField.VIDEO_WATCH_STATE ->
                adapter.readWatchState(record.accountNamespace, record.mediaId)?.updatedAtMs
        }
        if (storedTimestamp != null && record.updatedAtMs <= storedTimestamp) {
            return@synchronized CompanionSyncApplyResult.IgnoredStale
        }

        when (record.field) {
            CompanionSyncField.VIDEO_POS_DUR -> adapter.writePosition(
                accountNamespace = record.accountNamespace,
                mediaId = record.mediaId,
                positionMs = record.positionMs!!,
                durationMs = record.durationMs!!,
                updatedAtMs = record.updatedAtMs,
            )
            CompanionSyncField.VIDEO_WATCH_STATE -> adapter.writeWatchState(
                accountNamespace = record.accountNamespace,
                mediaId = record.mediaId,
                state = record.watchState!!,
                updatedAtMs = record.updatedAtMs,
            )
        }
        CompanionSyncApplyResult.Applied
    }

    fun read(
        accountNamespace: String,
        field: CompanionSyncField,
        mediaId: Int,
    ): CompanionSyncRecord? = synchronized(lock) {
        if (!isValidAccountNamespace(accountNamespace) || mediaId <= 0) return@synchronized null
        when (field) {
            CompanionSyncField.VIDEO_POS_DUR ->
                adapter.readPosition(accountNamespace, mediaId)?.let {
                    CompanionSyncRecord(
                        accountNamespace = accountNamespace,
                        field = field,
                        mediaId = mediaId,
                        positionMs = it.positionMs,
                        durationMs = it.durationMs,
                        updatedAtMs = it.updatedAtMs,
                    )
                }
            CompanionSyncField.VIDEO_WATCH_STATE ->
                adapter.readWatchState(accountNamespace, mediaId)?.let {
                    CompanionSyncRecord(
                        accountNamespace = accountNamespace,
                        field = field,
                        mediaId = mediaId,
                        watchState = it.state,
                        updatedAtMs = it.updatedAtMs,
                    )
                }
        }
    }

    private fun validate(
        currentAccountNamespace: String,
        record: CompanionSyncRecord,
    ): CompanionSyncRejectReason? {
        if (record.accountNamespace != currentAccountNamespace) {
            return CompanionSyncRejectReason.ACCOUNT_MISMATCH
        }
        if (!isValidAccountNamespace(record.accountNamespace)) {
            return CompanionSyncRejectReason.INVALID_ACCOUNT_NAMESPACE
        }
        if (record.mediaId <= 0) return CompanionSyncRejectReason.INVALID_MEDIA_ID
        if (record.updatedAtMs < 0L) return CompanionSyncRejectReason.INVALID_TIMESTAMP
        if (record.updatedAtMs > clock.nowMs() + COMPANION_SYNC_MAX_CLOCK_SKEW_MS) {
            return CompanionSyncRejectReason.FUTURE_TIMESTAMP
        }

        return when (record.field) {
            CompanionSyncField.VIDEO_POS_DUR -> {
                val position = record.positionMs
                val duration = record.durationMs
                if (record.watchState != null || position == null || duration == null ||
                    position < 0L || duration < COMPANION_SYNC_MIN_DURATION_MS ||
                    position > duration
                ) {
                    CompanionSyncRejectReason.INVALID_POSITION
                } else {
                    null
                }
            }
            CompanionSyncField.VIDEO_WATCH_STATE -> {
                if (record.positionMs != null || record.durationMs != null ||
                    record.watchState == null
                ) {
                    CompanionSyncRejectReason.INVALID_WATCH_STATE
                } else {
                    null
                }
            }
        }
    }

    private fun isValidAccountNamespace(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all {
            it.isLetterOrDigit() || it == '.' || it == '_' || it == '-'
        }
}
