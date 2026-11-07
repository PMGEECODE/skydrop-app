package com.famage.skydrop.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferHistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddAndListHistory() {
        val dir = tempFolder.newFolder("history_test")
        val store = TransferHistoryStore(dir, maxItems = 3)

        assertTrue(store.history.value.isEmpty())

        store.addEntry(
            TransferHistoryItem(
                id = "1",
                direction = "inbound",
                itemType = "file",
                name = "test1.jpg",
                size = 1000,
                peerName = "Linux Laptop",
                status = "completed"
            )
        )

        store.addEntry(
            TransferHistoryItem(
                id = "2",
                direction = "outbound",
                itemType = "file",
                name = "test2.png",
                size = 2000,
                peerName = "Linux Laptop",
                status = "completed"
            )
        )

        assertEquals(2, store.history.value.size)
        // Newest entry first
        assertEquals("test2.png", store.history.value[0].name)

        // Exceed max items (3)
        store.addEntry(
            TransferHistoryItem(
                id = "3",
                direction = "inbound",
                itemType = "text",
                name = "Hello link",
                size = 10,
                peerName = "Linux Laptop",
                status = "completed"
            )
        )
        store.addEntry(
            TransferHistoryItem(
                id = "4",
                direction = "outbound",
                itemType = "file",
                name = "test4.pdf",
                size = 5000,
                peerName = "Linux Laptop",
                status = "completed"
            )
        )

        assertEquals(3, store.history.value.size)
        assertEquals("test4.pdf", store.history.value[0].name)

        // Test persistence by reloading a new instance pointing to same dir
        val reloaded = TransferHistoryStore(dir, maxItems = 10)
        assertEquals(3, reloaded.history.value.size)
        assertEquals("test4.pdf", reloaded.history.value[0].name)

        // Test clear
        store.clear()
        assertTrue(store.history.value.isEmpty())
        val afterClear = TransferHistoryStore(dir, maxItems = 10)
        assertTrue(afterClear.history.value.isEmpty())
    }

    @Test
    fun testReloadHistory() {
        val dir = tempFolder.newFolder("history_reload_test")
        val store1 = TransferHistoryStore(dir, maxItems = 5)
        val store2 = TransferHistoryStore(dir, maxItems = 5)

        assertEquals(0, store1.history.value.size)
        assertEquals(0, store2.history.value.size)

        // Store1 adds entry to disk
        store1.addEntry(
            TransferHistoryItem(
                id = "reload_1",
                direction = "inbound",
                itemType = "file",
                name = "incoming.mp4",
                size = 1048576,
                peerName = "Desktop",
                status = "completed"
            )
        )

        assertEquals(1, store1.history.value.size)
        // Store2 reloads from disk
        store2.reload()
        assertEquals(1, store2.history.value.size)
        assertEquals("incoming.mp4", store2.history.value[0].name)
    }
}
