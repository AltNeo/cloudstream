package com.lagradost.cloudstream3.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionShareIntentTest {
    @Test
    fun `extracts url from shared prose and strips punctuation`() {
        assertEquals(
            "https://example.com/watch?id=7",
            CompanionShareIntent.extractUrlFromText(
                "Watch this: https://example.com/watch?id=7.",
            ),
        )
    }

    @Test
    fun `rejects non web urls`() {
        assertNull(CompanionShareIntent.extractUrlFromText("magnet:?xt=urn:btih:abc"))
        assertNull(CompanionShareIntent.extractUrlFromText("not a URL"))
    }
}
