package com.famage.skydrop.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class QueuedTransfer(
    val id: String = UUID.randomUUID().toString(),
    val targetDeviceId: String,
    val targetDeviceName: String,
    val uriString: String,
    val filename: String,
    val size: Long,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Manages persistent send queue across app restarts.
 * Dispatches queued files when presence transitions to ONLINE.
 */
class PersistentQueueManager(context: Context) {

    companion object {
        private const val TAG = "PersistentQueueManager"
        private const val FILE_NAME = "queued_transfers.json"
    }

    private val queueFile = File(context.filesDir, FILE_NAME)
    private val _queuedTransfers = MutableStateFlow<List<QueuedTransfer>>(emptyList())
    val queuedTransfers: StateFlow<List<QueuedTransfer>> = _queuedTransfers.asStateFlow()

    init {
        loadQueue()
    }

    @Synchronized
    fun enqueue(transfer: QueuedTransfer) {
        val current = _queuedTransfers.value.toMutableList()
        current.add(transfer)
        saveQueue(current)
    }

    @Synchronized
    fun dequeue(id: String) {
        val current = _queuedTransfers.value.toMutableList()
        current.removeAll { it.id == id }
        saveQueue(current)
    }

    @Synchronized
    fun getTransfersForDevice(deviceId: String): List<QueuedTransfer> {
        return _queuedTransfers.value.filter { it.targetDeviceId == deviceId }
    }

    private fun loadQueue() {
        if (!queueFile.exists()) {
            _queuedTransfers.value = emptyList()
            return
        }

        try {
            val content = queueFile.readText()
            val arr = JSONArray(content)
            val list = mutableListOf<QueuedTransfer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    QueuedTransfer(
                        id = obj.getString("id"),
                        targetDeviceId = obj.getString("target_device_id"),
                        targetDeviceName = obj.optString("target_device_name", "Device"),
                        uriString = obj.getString("uri_string"),
                        filename = obj.getString("filename"),
                        size = obj.optLong("size", 0L),
                        mimeType = obj.optString("mime_type", "*/*"),
                        createdAt = obj.optLong("created_at", System.currentTimeMillis())
                    )
                )
            }
            _queuedTransfers.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queued transfers", e)
            _queuedTransfers.value = emptyList()
        }
    }

    private fun saveQueue(list: List<QueuedTransfer>) {
        _queuedTransfers.value = list
        try {
            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("target_device_id", item.targetDeviceId)
                    put("target_device_name", item.targetDeviceName)
                    put("uri_string", item.uriString)
                    put("filename", item.filename)
                    put("size", item.size)
                    put("mime_type", item.mimeType)
                    put("created_at", item.createdAt)
                }
                arr.put(obj)
            }
            queueFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queued transfers", e)
        }
    }
}
