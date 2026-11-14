package com.famage.skydrop.clipboard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ClipboardStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val items: StateFlow<List<ClipboardItem>> = _items.asStateFlow()

    private val _syncEnabled = MutableStateFlow(true)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    private val _autoAddOnOpen = MutableStateFlow(false)
    val autoAddOnOpen: StateFlow<Boolean> = _autoAddOnOpen.asStateFlow()

    private val _historySize = MutableStateFlow(DEFAULT_HISTORY_SIZE)
    val historySize: StateFlow<Int> = _historySize.asStateFlow()

    companion object {
        private const val PREFS_NAME = "skydrop_clipboard_prefs"
        private const val KEY_ITEMS = "clipboard_items_json"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_AUTO_ADD = "auto_add_on_open"
        private const val KEY_HISTORY_SIZE = "history_size"
        const val MAX_TEXT_BYTES = 64 * 1024 // 64 KB limit
        const val DEFAULT_HISTORY_SIZE = 50

        @Volatile
        private var INSTANCE: ClipboardStore? = null

        fun getInstance(context: Context): ClipboardStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        loadSettings()
        loadItems()
    }

    private fun loadSettings() {
        _syncEnabled.value = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        _autoAddOnOpen.value = prefs.getBoolean(KEY_AUTO_ADD, false)
        _historySize.value = prefs.getInt(KEY_HISTORY_SIZE, DEFAULT_HISTORY_SIZE)
    }

    @Synchronized
    fun updateSettings(sync: Boolean, autoAdd: Boolean, size: Int) {
        val boundedSize = if (size in 10..500) size else DEFAULT_HISTORY_SIZE
        prefs.edit()
            .putBoolean(KEY_SYNC_ENABLED, sync)
            .putBoolean(KEY_AUTO_ADD, autoAdd)
            .putInt(KEY_HISTORY_SIZE, boundedSize)
            .apply()

        _syncEnabled.value = sync
        _autoAddOnOpen.value = autoAdd
        _historySize.value = boundedSize

        // Re-enforce cap if size decreased
        enforceCap()
    }

    @Synchronized
    fun add(item: ClipboardItem): Boolean {
        val trimmed = item.text.trim()
        if (trimmed.isEmpty()) return false
        if (item.text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return false

        val currentList = _items.value.toMutableList()

        // Loop prevention & deduplication check
        val existingIndex = currentList.indexOfFirst {
            it.id == item.id ||
            (item.originId.isNotEmpty() && it.originId == item.originId) ||
            it.text == item.text
        }

        if (existingIndex != -1) {
            // Already present: bump timestamp if newer
            val existing = currentList[existingIndex]
            if (item.createdAt > existing.createdAt) {
                currentList[existingIndex] = existing.copy(createdAt = item.createdAt)
                persist(currentList)
            }
            return false
        }

        val id = if (item.id.isBlank()) UUID.randomUUID().toString() else item.id
        val originId = if (item.originId.isBlank()) id else item.originId
        val timestamp = if (item.createdAt <= 0L) System.currentTimeMillis() else item.createdAt

        val newItem = item.copy(id = id, originId = originId, createdAt = timestamp)
        currentList.add(0, newItem)

        val cappedList = enforceCapList(currentList, _historySize.value)
        persist(cappedList)
        return true
    }

    @Synchronized
    fun delete(id: String) {
        val filtered = _items.value.filter { it.id != id }
        if (filtered.size != _items.value.size) {
            persist(filtered)
        }
    }

    @Synchronized
    fun pin(id: String, pinned: Boolean) {
        val updated = _items.value.map {
            if (it.id == id) it.copy(pinned = pinned) else it
        }
        persist(updated)
    }

    @Synchronized
    fun clear(keepPinned: Boolean) {
        val filtered = if (keepPinned) {
            _items.value.filter { it.pinned }
        } else {
            emptyList()
        }
        persist(filtered)
    }

    @Synchronized
    fun getIds(): List<String> {
        return _items.value.map { it.id }
    }

    @Synchronized
    fun getMissing(remoteIds: List<String>): List<ClipboardItem> {
        val remoteSet = remoteIds.toSet()
        return _items.value.filter { it.id !in remoteSet }
    }

    private fun enforceCap() {
        val current = _items.value
        val capped = enforceCapList(current, _historySize.value)
        if (capped.size != current.size) {
            persist(capped)
        }
    }

    private fun enforceCapList(list: List<ClipboardItem>, cap: Int): List<ClipboardItem> {
        val pinned = list.filter { it.pinned }
        val unpinned = list.filter { !it.pinned }

        val cappedUnpinned = if (unpinned.size > cap) unpinned.take(cap) else unpinned
        return (pinned + cappedUnpinned).sortedWith(
            compareByDescending<ClipboardItem> { it.pinned }
                .thenByDescending { it.createdAt }
        )
    }

    private fun loadItems() {
        val rawJson = prefs.getString(KEY_ITEMS, null) ?: return
        val list = mutableListOf<ClipboardItem>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ClipboardItem(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        sourceDevice = obj.optString("source_device", "Unknown"),
                        createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                        pinned = obj.optBoolean("pinned", false),
                        originId = obj.optString("origin_id", obj.getString("id"))
                    )
                )
            }
        } catch (_: Exception) {
            // Keep empty list if corrupted
        }

        _items.value = enforceCapList(list, _historySize.value)
    }

    private fun persist(list: List<ClipboardItem>) {
        val sortedList = list.sortedWith(
            compareByDescending<ClipboardItem> { it.pinned }
                .thenByDescending { it.createdAt }
        )
        val array = JSONArray()
        for (item in sortedList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("source_device", item.sourceDevice)
                put("created_at", item.createdAt)
                put("pinned", item.pinned)
                put("origin_id", item.originId)
            }
            array.put(obj)
        }

        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
        _items.value = sortedList
    }
}
