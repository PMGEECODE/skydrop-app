package com.famage.skydrop.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.famage.skydrop.identity.DeviceIdentity
import com.famage.skydrop.security.PairedDeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class SyncStatus {
    SYNCED,
    OFFLINE,
    ERROR,
    SYNCING
}

class ClipboardViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ClipboardStore.getInstance(application)
    private val syncClient = ClipboardSyncClient()
    private val pairedStore = PairedDeviceStore(application)

    val items: StateFlow<List<ClipboardItem>> = store.items
    val syncEnabled: StateFlow<Boolean> = store.syncEnabled
    val autoAddOnOpen: StateFlow<Boolean> = store.autoAddOnOpen
    val historySize: StateFlow<Int> = store.historySize

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        syncWithPeers()
    }

    fun addLocal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val id = UUID.randomUUID().toString()
        val deviceName = DeviceIdentity.getDeviceName()
        val item = ClipboardItem(
            id = id,
            text = trimmed,
            sourceDevice = deviceName,
            createdAt = System.currentTimeMillis(),
            pinned = false,
            originId = id
        )

        val added = store.add(item)
        if (added && syncEnabled.value) {
            broadcastToPeers(item)
        }
    }

    fun addFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        if (!cm.hasPrimaryClip()) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val clip: ClipData = cm.primaryClip ?: return
        if (clip.itemCount == 0) return

        val desc = clip.description
        // Check for sensitive or password flag
        var isSensitive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isSensitive = desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
        }
        val label = desc.label?.toString()?.lowercase() ?: ""
        if (isSensitive || label.contains("password") || label.contains("secret") || desc.hasMimeType("application/x-password")) {
            Toast.makeText(context, "Sensitive clipboard content skipped", Toast.LENGTH_SHORT).show()
            return
        }

        val textItem = clip.getItemAt(0)
        val text = textItem.text?.toString() ?: textItem.coerceToText(context)?.toString() ?: ""
        if (text.isNotBlank()) {
            addLocal(text)
            Toast.makeText(context, "Added to clipboard sync", Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(id: String) {
        store.delete(id)
    }

    fun pin(id: String, pinned: Boolean) {
        store.pin(id, pinned)
    }

    fun clear(keepPinned: Boolean) {
        store.clear(keepPinned)
    }

    fun updateSettings(sync: Boolean, autoAdd: Boolean, size: Int) {
        store.updateSettings(sync, autoAdd, size)
    }

    fun syncWithPeers() {
        if (!syncEnabled.value) {
            _syncStatus.value = SyncStatus.OFFLINE
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _syncStatus.value = SyncStatus.SYNCING
            val peers = pairedStore.pairedDevices.value
            if (peers.isEmpty()) {
                _syncStatus.value = SyncStatus.SYNCED
                return@launch
            }

            var hasSuccess = false
            var hasFailure = false

            for (peer in peers) {
                val scheme = if (peer.isTls) "https" else "http"
                val baseUrl = "$scheme://${peer.host}:${peer.port}"

                val syncResult = syncClient.syncIds(baseUrl, peer.authToken)
                if (syncResult.isSuccess) {
                    hasSuccess = true
                    val remoteIds = syncResult.getOrThrow()
                    val missing = store.getMissing(remoteIds)
                    if (missing.isNotEmpty()) {
                        syncClient.pushMissing(baseUrl, peer.authToken, missing)
                    }
                } else {
                    hasFailure = true
                }
            }

            _syncStatus.value = when {
                hasSuccess -> SyncStatus.SYNCED
                hasFailure -> SyncStatus.OFFLINE
                else -> SyncStatus.SYNCED
            }
        }
    }

    private fun broadcastToPeers(item: ClipboardItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val peers = pairedStore.pairedDevices.value
            for (peer in peers) {
                val scheme = if (peer.isTls) "https" else "http"
                val baseUrl = "$scheme://${peer.host}:${peer.port}"
                syncClient.postItem(baseUrl, peer.authToken, item)
            }
        }
    }
}
