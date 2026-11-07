package com.famage.skydrop.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TransferHistoryStore(
    private val baseDir: File,
    private val maxItems: Int = 200
) {
    constructor(context: Context, maxItems: Int = 200) : this(context.filesDir, maxItems)

    companion object {
        private const val TAG = "TransferHistoryStore"
        private const val FILE_NAME = "transfer_history.json"

        @Volatile
        private var instance: TransferHistoryStore? = null

        fun getInstance(context: Context, maxItems: Int = 200): TransferHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: TransferHistoryStore(context.applicationContext.filesDir, maxItems).also { instance = it }
            }
        }
    }

    private val historyFile: File by lazy {
        File(baseDir, FILE_NAME)
    }

    private val _history = MutableStateFlow<List<TransferHistoryItem>>(emptyList())
    val history: StateFlow<List<TransferHistoryItem>> = _history.asStateFlow()

    init {
        loadHistory()
    }

    @Synchronized
    fun reload() {
        loadHistory()
    }

    @Synchronized
    fun addEntry(item: TransferHistoryItem) {
        val current = _history.value.toMutableList()
        current.removeAll { it.id == item.id || (!item.uri.isNullOrBlank() && it.uri == item.uri) }
        current.add(0, item) // newest first

        val trimmed = if (current.size > maxItems) {
            current.subList(0, maxItems)
        } else {
            current
        }

        _history.value = trimmed
        saveHistory(trimmed)
    }

    @Synchronized
    fun deleteEntry(id: String) {
        val current = _history.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _history.value = current
            saveHistory(current)
        }
    }

    @Synchronized
    fun deleteEntries(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = _history.value.toMutableList()
        val idSet = ids.toSet()
        val removed = current.removeAll { idSet.contains(it.id) }
        if (removed) {
            _history.value = current
            saveHistory(current)
        }
    }

    @Synchronized
    fun clear() {
        _history.value = emptyList()
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }

    @Synchronized
    private fun loadHistory() {
        if (!historyFile.exists()) {
            _history.value = emptyList()
            return
        }

        try {
            val content = historyFile.readText()
            if (content.isBlank()) {
                _history.value = emptyList()
                return
            }

            val jsonArray = JSONArray(content)
            val list = mutableListOf<TransferHistoryItem>()
            val seenIds = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawId = obj.optString("id", "")
                val id = if (rawId.isNotBlank() && !seenIds.contains(rawId)) {
                    rawId
                } else {
                    "hist_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                }
                seenIds.add(id)
                list.add(
                    TransferHistoryItem(
                        id = id,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        direction = obj.optString("direction", "inbound"),
                        itemType = obj.optString("itemType", "file"),
                        name = obj.optString("name", "Unknown"),
                        size = obj.optLong("size", 0L),
                        peerName = obj.optString("peerName", "Unknown"),
                        status = obj.optString("status", "completed"),
                        errorMessage = if (obj.has("errorMessage") && !obj.isNull("errorMessage")) {
                            obj.getString("errorMessage")
                        } else null,
                        uri = if (obj.has("uri") && !obj.isNull("uri")) obj.getString("uri") else null,
                        mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null
                    )
                )
            }
            _history.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load transfer history", e)
            _history.value = emptyList()
        }
    }

    @Synchronized
    private fun saveHistory(items: List<TransferHistoryItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("direction", item.direction)
                    put("itemType", item.itemType)
                    put("name", item.name)
                    put("size", item.size)
                    put("peerName", item.peerName)
                    put("status", item.status)
                    if (item.errorMessage != null) {
                        put("errorMessage", item.errorMessage)
                    }
                    if (item.uri != null) {
                        put("uri", item.uri)
                    }
                    if (item.mimeType != null) {
                        put("mimeType", item.mimeType)
                    }
                }
                jsonArray.put(obj)
            }

            val tempFile = File(baseDir, "$FILE_NAME.tmp")
            tempFile.writeText(jsonArray.toString(2))
            if (tempFile.exists()) {
                tempFile.renameTo(historyFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transfer history", e)
        }
    }
}
