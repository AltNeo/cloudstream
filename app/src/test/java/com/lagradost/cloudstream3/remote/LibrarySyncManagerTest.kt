package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncManagerTest {

    // ------------------------------------------------------------------
    // shouldApplyRemoteEntry (LWW + both-zero bootstrap, review R1)
    // ------------------------------------------------------------------

    @Test
    fun `newer remote timestamp wins`() {
        assertTrue(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 200,
                remoteValueJson = "{\"x\":1}",
                localTs = 100,
                localValue = "{\"x\":0}",
            )
        )
    }

    @Test
    fun `older or equal remote timestamp loses`() {
        assertFalse(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 100,
                remoteValueJson = "{\"x\":1}",
                localTs = 100,
                localValue = "{\"x\":1}",
            )
        )
        assertFalse(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 50,
                remoteValueJson = "{\"x\":1}",
                localTs = 100,
                localValue = "{\"x\":1}",
            )
        )
    }

    @Test
    fun `bootstrap copies remote value when local value is absent`() {
        assertTrue(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 0,
                remoteValueJson = "{\"x\":1}",
                localTs = 0,
                localValue = null,
            )
        )
    }

    @Test
    fun `bootstrap keeps the local value when present`() {
        assertFalse(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 0,
                remoteValueJson = "{\"x\":1}",
                localTs = 0,
                localValue = "{\"x\":2}",
            )
        )
    }

    @Test
    fun `bootstrap never materializes a remote tombstone`() {
        assertFalse(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 0,
                remoteValueJson = null,
                localTs = 0,
                localValue = null,
            )
        )
    }

    @Test
    fun `unknown remote timestamp loses to a real local timestamp`() {
        assertFalse(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 0,
                remoteValueJson = "{\"x\":1}",
                localTs = 500,
                localValue = "{\"x\":9}",
            )
        )
    }

    @Test
    fun `known remote timestamp wins over unknown local`() {
        assertTrue(
            LibrarySyncManager.shouldApplyRemoteEntry(
                remoteUpdatedAtMs = 500,
                remoteValueJson = "{\"x\":1}",
                localTs = 0,
                localValue = "{\"x\":9}",
            )
        )
    }

    // ------------------------------------------------------------------
    // chunkEntries (oversized full-dump paging, review R5)
    // ------------------------------------------------------------------

    private fun entry(key: String) = LibraryEntry(
        key = key,
        valueJson = "{\"n\":\"$key\"}",
        updatedAtMs = 1L,
    )

    @Test
    fun `small payload is not chunked`() {
        val pages = LibrarySyncManager.chunkEntries(
            listOf(entry("a"), entry("b")),
            budgetBytes = 1024 * 1024,
        )
        assertEquals(1, pages.size)
        assertEquals(listOf("a", "b"), pages[0].entries.map { it.key })
    }

    @Test
    fun `oversized payload is chunked into bounded sorted pages`() {
        val entries = (0 until 50).map { entry("key-%02d".format(it)) }
        val pages = LibrarySyncManager.chunkEntries(entries, budgetBytes = 128)
        assertTrue("expected multiple pages, got ${pages.size}", pages.size > 1)

        val json = LanRemoteProtocol.json
        pages.forEach { page ->
            val size = page.entries.sumOf { e ->
                json.encodeToString(LibraryEntry.serializer(), e).length
            }
            assertTrue("page exceeds budget: $size", size <= 128)
        }

        val allKeys = pages.flatMap { it.entries.map { e -> e.key } }
        assertEquals(entries.map { it.key }.sorted(), allKeys)
        pages.forEach { page ->
            assertEquals(page.entries.map { it.key }, page.entries.map { it.key }.sorted())
        }
    }

    @Test
    fun `library payload rejects keys outside the documented groups`() {
        val payload = LibrarySyncPayload(
            full = false,
            entries = listOf(entry("unrelated_setting/1")),
        )
        assertFalse(LibrarySyncManager.validatePayload(payload, nowMs = 1_000L))
    }

    @Test
    fun `library payload accepts a bounded documented value`() {
        assertTrue(
            LibrarySyncManager.validatePayload(
                LibrarySyncPayload(
                    full = false,
                    entries = listOf(LibraryEntry("video_pos_dur/1", "{\"position\":1,\"duration\":2}", 1_000L)),
                ),
                nowMs = 1_000L,
            )
        )
    }

    @Test
    fun `library payload rejects malformed values and future timestamps`() {
        assertFalse(
            LibrarySyncManager.validatePayload(
                LibrarySyncPayload(
                    full = false,
                    entries = listOf(LibraryEntry("video_pos_dur/1", "{\"position\":\"bad\"}", 1_000L)),
                ),
                nowMs = 1_000L,
            )
        )
        assertFalse(
            LibrarySyncManager.validatePayload(
                LibrarySyncPayload(
                    full = false,
                    entries = listOf(
                        LibraryEntry(
                            "video_pos_dur/1",
                            "{\"position\":1,\"duration\":2}",
                            1_000L + 10 * 60 * 1000L,
                        )
                    ),
                ),
                nowMs = 1_000L,
            )
        )
    }
}
