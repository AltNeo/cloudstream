package com.lagradost.cloudstream3.companion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionSyncEngineTest {
    @Test
    fun `position record is typed and account namespaced`() {
        val adapter = InMemoryCompanionDataStoreHelperAdapter()
        val engine = CompanionSyncEngine(adapter, CompanionSyncClock { 1_000L })
        val record = CompanionSyncRecord(
            accountNamespace = "0",
            field = CompanionSyncField.VIDEO_POS_DUR,
            mediaId = 12,
            positionMs = 5_000L,
            durationMs = 60_000L,
            updatedAtMs = 900L,
        )

        assertEquals(CompanionSyncApplyResult.Applied, engine.apply("0", record))
        assertEquals("0/video_pos_dur/12", record.storageKey())
        assertEquals(record, engine.read("0", CompanionSyncField.VIDEO_POS_DUR, 12))
        assertNull(engine.read("1", CompanionSyncField.VIDEO_POS_DUR, 12))
    }

    @Test
    fun `last write wins and equal timestamps do not overwrite`() {
        val adapter = InMemoryCompanionDataStoreHelperAdapter()
        val engine = CompanionSyncEngine(adapter, CompanionSyncClock { 10_000L })
        val base = CompanionSyncRecord(
            accountNamespace = "0",
            field = CompanionSyncField.VIDEO_POS_DUR,
            mediaId = 1,
            positionMs = 10,
            durationMs = 30_000,
            updatedAtMs = 100,
        )

        assertEquals(CompanionSyncApplyResult.Applied, engine.apply("0", base))
        assertEquals(
            CompanionSyncApplyResult.IgnoredStale,
            engine.apply("0", base.copy(positionMs = 20)),
        )
        assertEquals(
            CompanionSyncApplyResult.IgnoredStale,
            engine.apply("0", base.copy(positionMs = 5, updatedAtMs = 99)),
        )
        assertEquals(
            CompanionSyncApplyResult.Applied,
            engine.apply("0", base.copy(positionMs = 25, updatedAtMs = 101)),
        )
        assertEquals(25L, engine.read("0", CompanionSyncField.VIDEO_POS_DUR, 1)?.positionMs)
    }

    @Test
    fun `future clock skew and wrong account are rejected`() {
        val engine = CompanionSyncEngine(
            InMemoryCompanionDataStoreHelperAdapter(),
            CompanionSyncClock { 1_000L },
        )
        val valid = CompanionSyncRecord(
            accountNamespace = "0",
            field = CompanionSyncField.VIDEO_WATCH_STATE,
            mediaId = 2,
            watchState = CompanionWatchState.WATCHED,
            updatedAtMs = 1_000L,
        )

        assertEquals(
            CompanionSyncApplyResult.Rejected(CompanionSyncRejectReason.ACCOUNT_MISMATCH),
            engine.apply("1", valid),
        )
        assertEquals(
            CompanionSyncApplyResult.Rejected(CompanionSyncRejectReason.FUTURE_TIMESTAMP),
            engine.apply("0", valid.copy(updatedAtMs = 301_001L)),
        )
    }

    @Test
    fun `invalid fields cannot smuggle arbitrary preferences`() {
        val engine = CompanionSyncEngine(
            InMemoryCompanionDataStoreHelperAdapter(),
            CompanionSyncClock { 1_000L },
        )
        val invalidPosition = CompanionSyncRecord(
            accountNamespace = "0/other",
            field = CompanionSyncField.VIDEO_POS_DUR,
            mediaId = 1,
            positionMs = 4,
            durationMs = 5,
            updatedAtMs = 0,
        )
        val invalidWatch = CompanionSyncRecord(
            accountNamespace = "0",
            field = CompanionSyncField.VIDEO_WATCH_STATE,
            mediaId = 1,
            positionMs = 1,
            watchState = CompanionWatchState.WATCHED,
            updatedAtMs = 0,
        )

        assertEquals(
            CompanionSyncApplyResult.Rejected(CompanionSyncRejectReason.INVALID_ACCOUNT_NAMESPACE),
            engine.apply("0/other", invalidPosition),
        )
        assertEquals(
            CompanionSyncApplyResult.Rejected(CompanionSyncRejectReason.INVALID_WATCH_STATE),
            engine.apply("0", invalidWatch),
        )
    }
}

private class InMemoryCompanionDataStoreHelperAdapter : CompanionDataStoreHelperAdapter {
    private val positions = mutableMapOf<Pair<String, Int>, StoredCompanionPosition>()
    private val states = mutableMapOf<Pair<String, Int>, StoredCompanionWatchState>()

    override fun readPosition(accountNamespace: String, mediaId: Int): StoredCompanionPosition? =
        positions[accountNamespace to mediaId]

    override fun writePosition(
        accountNamespace: String,
        mediaId: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAtMs: Long,
    ) {
        positions[accountNamespace to mediaId] =
            StoredCompanionPosition(positionMs, durationMs, updatedAtMs)
    }

    override fun readWatchState(
        accountNamespace: String,
        mediaId: Int,
    ): StoredCompanionWatchState? = states[accountNamespace to mediaId]

    override fun writeWatchState(
        accountNamespace: String,
        mediaId: Int,
        state: CompanionWatchState,
        updatedAtMs: Long,
    ) {
        states[accountNamespace to mediaId] = StoredCompanionWatchState(state, updatedAtMs)
    }
}
