package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.ui.player.PlaybackCoordinator
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    @Test
    fun tvLinksMustBeAbsoluteHttpUrls() {
        assertTrue(
            PlaybackCoordinator.isTvCompatibleUrl(
                "https://cdn.video-plex.xyz/?url=encoded-stream"
            )
        )
        assertTrue(PlaybackCoordinator.isTvCompatibleUrl("http://192.168.0.8:8080/video.m3u8"))
        assertFalse(PlaybackCoordinator.isTvCompatibleUrl("/login?ref=/file/episode"))
        assertFalse(PlaybackCoordinator.isTvCompatibleUrl("magnet:?xt=urn:btih:example"))
        assertFalse(PlaybackCoordinator.isTvCompatibleUrl("not a url"))
    }

    @Test
    fun tvPayloadDropsInvalidAndUnsupportedLinks() {
        val valid = CloudStreamPackage.MinimalVideoLink(
            uri = null,
            url = "https://cdn.example.test/video.m3u8",
            mimeType = "application/x-mpegURL",
            name = "valid",
            quality = null,
        )
        val relative = valid.copy(url = "/login?ref=/file/episode")
        val torrent = valid.copy(
            url = "https://cdn.example.test/file.torrent",
            mimeType = "application/x-bittorrent",
        )

        assertTrue(PlaybackCoordinator.tvCompatibleLinks(listOf(valid, relative, torrent)) == listOf(valid))
    }
}
