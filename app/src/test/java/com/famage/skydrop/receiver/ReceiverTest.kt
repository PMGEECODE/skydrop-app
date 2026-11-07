package com.famage.skydrop.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceiverTest {

    @Test
    fun testFilenameSanitization() {
        val testCases = mapOf(
            "normal_file.jpg" to "normal_file.jpg",
            "../../../../etc/passwd" to "passwd",
            "folder/sub/song.mp3" to "song.mp3",
            "windows\\style\\path.pdf" to "path.pdf",
            "   spaced name.txt   " to "spaced name.txt"
        )

        for ((input, expected) in testCases) {
            val sanitized = ScopedStorageManager.sanitizeFilename(input)
            assertFalse("Sanitized filename must not contain slashes", sanitized.contains("/"))
            assertFalse("Sanitized filename must not contain backslashes", sanitized.contains("\\"))
            assertEquals(expected, sanitized)
        }
    }

    @Test
    fun testReceiverServiceConstants() {
        assertEquals("skydrop_receiver_channel", ReceiverService.CHANNEL_ID)
        assertEquals(1001, ReceiverService.NOTIFICATION_ID)
        assertEquals(8444, ReceiverService.DEFAULT_PORT)
        assertEquals("com.famage.skydrop.action.STOP_RECEIVER", ReceiverService.ACTION_STOP)
    }
}
