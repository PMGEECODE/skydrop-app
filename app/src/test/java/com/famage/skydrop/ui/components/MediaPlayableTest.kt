package com.famage.skydrop.ui.components

import com.famage.skydrop.history.TransferHistoryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlayableTest {

    private fun createItem(name: String, mime: String? = null): TransferHistoryItem {
        return TransferHistoryItem(
            id = "test-id",
            name = name,
            size = 1024L,
            peerName = "Laptop",
            itemType = "file",
            mimeType = mime,
            timestamp = System.currentTimeMillis(),
            direction = "inbound",
            status = "completed",
            uri = "/storage/emulated/0/Download/SkyDrop/$name"
        )
    }

    @Test
    fun `identifies mp4 video as playable`() {
        val item = createItem("clip.mp4", "video/mp4")
        assertTrue(isPlayableMedia(item))
        assertFalse(isAudioOnlyMedia(item))
    }

    @Test
    fun `identifies mkv video with null mime as playable`() {
        val item = createItem("movie.mkv", null)
        assertTrue(isPlayableMedia(item))
        assertFalse(isAudioOnlyMedia(item))
    }

    @Test
    fun `identifies mp3 as playable audio`() {
        val item = createItem("song.mp3", "audio/mpeg")
        assertTrue(isPlayableMedia(item))
        assertTrue(isAudioOnlyMedia(item))
    }

    @Test
    fun `identifies wav audio with null mime as playable`() {
        val item = createItem("recording.wav", null)
        assertTrue(isPlayableMedia(item))
        assertTrue(isAudioOnlyMedia(item))
    }

    @Test
    fun `pdf document is not playable media`() {
        val item = createItem("document.pdf", "application/pdf")
        assertFalse(isPlayableMedia(item))
        assertFalse(isAudioOnlyMedia(item))
    }

    @Test
    fun `jpg image is not playable media`() {
        val item = createItem("photo.jpg", "image/jpeg")
        assertFalse(isPlayableMedia(item))
        assertFalse(isAudioOnlyMedia(item))
    }

    @Test
    fun `zip archive is not playable media`() {
        val item = createItem("files.zip", "application/zip")
        assertFalse(isPlayableMedia(item))
        assertFalse(isAudioOnlyMedia(item))
    }
}
