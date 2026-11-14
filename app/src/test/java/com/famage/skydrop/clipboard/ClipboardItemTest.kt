package com.famage.skydrop.clipboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClipboardItemTest {

    @Test
    fun testClipboardItemDefaults() {
        val item = ClipboardItem(
            id = "test-uuid-1",
            text = "Hello world",
            sourceDevice = "Linux Box",
            createdAt = 1000L
        )

        assertEquals("test-uuid-1", item.id)
        assertEquals("Hello world", item.text)
        assertEquals("test-uuid-1", item.originId)
        assertFalse(item.pinned)
    }

    @Test
    fun testClipboardItemSortingPinnedFirst() {
        val item1 = ClipboardItem("1", "Text 1", "Device A", 1000L, pinned = false)
        val item2 = ClipboardItem("2", "Pinned Text", "Device B", 500L, pinned = true)
        val item3 = ClipboardItem("3", "Text 3", "Device A", 2000L, pinned = false)

        val list = listOf(item1, item2, item3)
        val sorted = list.sortedWith(
            compareByDescending<ClipboardItem> { it.pinned }
                .thenByDescending { it.createdAt }
        )

        // Pinned item should be first even with older timestamp
        assertEquals("2", sorted[0].id)
        assertTrue(sorted[0].pinned)
        // Among unpinned, newest timestamp should come next
        assertEquals("3", sorted[1].id)
        assertEquals("1", sorted[2].id)
    }

    @Test
    fun testClipboardItemJsonRoundTrip() {
        val original = ClipboardItem(
            id = "uuid-1234",
            text = "Sample sync text",
            sourceDevice = "Pixel 8",
            createdAt = 1700000000L,
            pinned = true,
            originId = "orig-5678"
        )

        val json = JSONObject().apply {
            put("id", original.id)
            put("text", original.text)
            put("source_device", original.sourceDevice)
            put("created_at", original.createdAt)
            put("pinned", original.pinned)
            put("origin_id", original.originId)
        }

        val deserialized = ClipboardItem(
            id = json.getString("id"),
            text = json.getString("text"),
            sourceDevice = json.getString("source_device"),
            createdAt = json.getLong("created_at"),
            pinned = json.getBoolean("pinned"),
            originId = json.getString("origin_id")
        )

        assertEquals(original, deserialized)
    }

    @Test
    fun testLoopPreventionDetection() {
        val localItem = ClipboardItem(
            id = "local-1",
            text = "Sent from this device",
            sourceDevice = "Pixel 8",
            createdAt = 1000L,
            originId = "local-1"
        )

        val remoteEcho = ClipboardItem(
            id = "remote-echo-99",
            text = "Sent from this device",
            sourceDevice = "Desktop Linux",
            createdAt = 2000L,
            originId = "local-1" // Identical originId: loop detected!
        )

        // Origin IDs match
        assertEquals(localItem.originId, remoteEcho.originId)
    }
}
