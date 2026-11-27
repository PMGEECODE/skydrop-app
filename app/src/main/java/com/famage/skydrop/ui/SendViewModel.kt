package com.famage.skydrop.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.famage.skydrop.discovery.NsdDiscoveryManager
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.PairedDevice
import com.famage.skydrop.model.SharedItem
import com.famage.skydrop.model.TransferState
import com.famage.skydrop.network.FileUploader
import com.famage.skydrop.security.PairedDeviceStore
import com.famage.skydrop.security.PairingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.famage.skydrop.model.PeerStatus
import com.famage.skydrop.ui.theme.ThemePreference
import com.famage.skydrop.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SendViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = NsdDiscoveryManager(application)
    private val fileUploader = FileUploader(application.contentResolver)
    private val pairedDeviceStore = PairedDeviceStore(application)
    private val pairingManager = PairingManager(pairedDeviceStore)
    private val historyStore = com.famage.skydrop.history.TransferHistoryStore.getInstance(application)

    val presenceManager: com.famage.skydrop.presence.PresenceManager =
        com.famage.skydrop.receiver.ReceiverService.activePresenceManager
            ?: com.famage.skydrop.presence.PresenceManager(application, pairedDeviceStore).also { it.start() }
    val queueManager = com.famage.skydrop.network.PersistentQueueManager(application)

    // ── Theme ────────────────────────────────────────────────────────────────
    private val themeStore = ThemePreferenceStore.getInstance(application)
    val themePreference: StateFlow<ThemePreference> = themeStore.themePreference

    fun setTheme(pref: ThemePreference) {
        themeStore.setPreference(pref)
    }
    // ────────────────────────────────────────────────────────────────────────

    val devices: StateFlow<List<Device>> = discoveryManager.devices
    val pairedDevices: StateFlow<List<PairedDevice>> = pairedDeviceStore.pairedDevices
    val incomingPairRequests: StateFlow<List<com.famage.skydrop.model.IncomingPairRequest>> =
        com.famage.skydrop.receiver.ReceiverService.activeIncomingRequests
    val transferHistory: StateFlow<List<com.famage.skydrop.history.TransferHistoryItem>> = historyStore.history

    val peerStatuses: StateFlow<List<PeerStatus>> = combine(
        discoveryManager.devices,
        pairedDeviceStore.pairedDevices,
        presenceManager.presences
    ) { discoveredList, pairedList, presenceMap ->
        val results = mutableListOf<PeerStatus>()
        val seenPairedIds = mutableSetOf<String>()

        for (device in discoveredList) {
            val matchingPaired = pairedList.firstOrNull { paired ->
                (!device.fingerprint.isNullOrBlank() && paired.fingerprint.equals(device.fingerprint, ignoreCase = true)) ||
                paired.id == device.id ||
                paired.host == device.host ||
                paired.name.equals(device.name, ignoreCase = true)
            }

            if (matchingPaired != null) {
                seenPairedIds.add(matchingPaired.id)
                val pres = presenceMap[matchingPaired.id] ?: presenceMap[device.id]
                results.add(
                    PeerStatus(
                        id = matchingPaired.id,
                        name = matchingPaired.name.ifBlank { device.name },
                        host = device.host.ifBlank { matchingPaired.host },
                        port = if (device.port > 0) device.port else matchingPaired.port,
                        isTls = device.isTls,
                        fingerprint = device.fingerprint?.takeIf { it.isNotBlank() } ?: matchingPaired.fingerprint,
                        isPaired = true,
                        isConnected = pres?.canSend ?: false,
                        authToken = matchingPaired.authToken,
                        presenceState = pres?.state ?: com.famage.skydrop.presence.PresenceState.UNKNOWN,
                        errorCode = pres?.errorCode,
                        errorMessage = pres?.errorMessage,
                        latencyMs = pres?.latencyMs ?: 0L,
                        lastSeenAt = pres?.lastSeenAt ?: 0L,
                        capabilities = pres?.capabilities ?: emptyList()
                    )
                )
            } else {
                results.add(
                    PeerStatus(
                        id = device.id,
                        name = device.name,
                        host = device.host,
                        port = device.port,
                        isTls = device.isTls,
                        fingerprint = device.fingerprint,
                        isPaired = false,
                        isConnected = false,
                        authToken = null,
                        presenceState = com.famage.skydrop.presence.PresenceState.UNKNOWN
                    )
                )
            }
        }

        for (paired in pairedList) {
            if (!seenPairedIds.contains(paired.id)) {
                val pres = presenceMap[paired.id] ?: presenceMap.values.firstOrNull {
                    (it.host.isNotBlank() && it.host == paired.host) || it.deviceName.equals(paired.name, ignoreCase = true)
                }
                results.add(
                    PeerStatus(
                        id = paired.id,
                        name = paired.name,
                        host = pres?.host?.takeIf { it.isNotBlank() } ?: paired.host,
                        port = if ((pres?.port ?: 0) > 0) pres!!.port else paired.port,
                        isTls = paired.isTls,
                        fingerprint = paired.fingerprint,
                        isPaired = true,
                        isConnected = pres?.canSend ?: false,
                        authToken = paired.authToken,
                        presenceState = pres?.state ?: com.famage.skydrop.presence.PresenceState.UNKNOWN,
                        errorCode = pres?.errorCode,
                        errorMessage = pres?.errorMessage,
                        latencyMs = pres?.latencyMs ?: 0L,
                        lastSeenAt = pres?.lastSeenAt ?: 0L,
                        capabilities = pres?.capabilities ?: emptyList()
                    )
                )
            }
        }

        results.distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            discoveryManager.devices.collect { devList ->
                devList.forEach { dev ->
                    presenceManager.onDiscoveredEndpoint(dev.id, dev.name, dev.host, dev.port, dev.isTls)
                }
            }
        }
        viewModelScope.launch {
            peerStatuses.collect { statuses ->
                statuses.filter { it.canSend }.forEach { readyPeer ->
                    val pending = queueManager.getTransfersForDevice(readyPeer.id)
                    for (item in pending) {
                        queueManager.dequeue(item.id)
                        launch {
                            val uri = Uri.parse(item.uriString)
                            val sharedItem = SharedItem(
                                uri = uri,
                                name = item.filename,
                                size = item.size,
                                mimeType = item.mimeType
                            )
                            val dev = readyPeer.toDevice()
                            val token = getPairedToken(dev)
                            val result = fileUploader.uploadFile(dev, sharedItem, token)
                            if (result.isSuccess) {
                                presenceManager.recordActivity(readyPeer.id)
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            com.famage.skydrop.receiver.ReceiverService.activeReceiveProgress.collect { prog ->
                if (prog == null) {
                    syncExistingDownloads()
                }
            }
        }
    }

    private val _queuedItems = MutableStateFlow<List<SharedItem>>(emptyList())
    val queuedItems: StateFlow<List<SharedItem>> = _queuedItems.asStateFlow()

    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val _isReceiverActive = MutableStateFlow(false)
    val isReceiverActive: StateFlow<Boolean> = _isReceiverActive.asStateFlow()

    fun clearHistory() {
        historyStore.clear()
    }

    fun deleteHistoryItem(id: String) {
        historyStore.deleteEntry(id)
    }

    fun deleteHistoryItems(ids: Collection<String>) {
        historyStore.deleteEntries(ids)
    }

    fun queueHistoryItemForSend(item: com.famage.skydrop.history.TransferHistoryItem) {
        val uriStr = item.uri
        if (!uriStr.isNullOrBlank()) {
            addSharedUris(listOf(android.net.Uri.parse(uriStr)))
        }
    }

    fun unpairDevice(id: String) {
        pairedDeviceStore.removeDevice(id)
    }

    fun toggleReceiver(enable: Boolean) {
        val app = getApplication<Application>()
        if (enable) {
            com.famage.skydrop.receiver.ReceiverService.startService(app)
        } else {
            com.famage.skydrop.receiver.ReceiverService.stopService(app)
        }
        _isReceiverActive.value = enable
    }

    fun startDiscovery() {
        discoveryManager.startDiscovery()
    }

    val receiveProgress: StateFlow<com.famage.skydrop.receiver.ReceiveProgress?> =
        com.famage.skydrop.receiver.ReceiverService.activeReceiveProgress.asStateFlow()

    init {
        syncExistingDownloads()
    }

    fun stopDiscovery() {
        discoveryManager.stopDiscovery()
    }

    fun setSharedUris(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val items = uris.mapNotNull { uri -> resolveSharedItem(resolver, uri) }.distinctBy { it.uri.toString() }
        _queuedItems.value = items
    }

    fun addSharedUris(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val newItems = uris.mapNotNull { uri -> resolveSharedItem(resolver, uri) }
        val current = _queuedItems.value.toMutableList()
        val existingUris = current.map { it.uri.toString() }.toSet()
        newItems.forEach { item ->
            if (!existingUris.contains(item.uri.toString())) {
                current.add(item)
            }
        }
        _queuedItems.value = current
    }

    fun removeQueuedItem(item: SharedItem) {
        val current = _queuedItems.value.toMutableList()
        current.remove(item)
        _queuedItems.value = current
    }

    fun clearQueuedItems() {
        _queuedItems.value = emptyList()
        _sharedText.value = null
    }

    fun syncExistingDownloads() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                historyStore.reload()
                val currentNames = historyStore.history.value.map { it.name }.toMutableSet()
                val currentIds = historyStore.history.value.map { it.id }.toMutableSet()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val projection = arrayOf(
                        android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME,
                        android.provider.MediaStore.Downloads.SIZE,
                        android.provider.MediaStore.Downloads.DATE_MODIFIED,
                        android.provider.MediaStore.Downloads.MIME_TYPE
                    )
                    val selection = "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                    val selectionArgs = arrayOf("%SkyDrop%")
                    val sortOrder = "${android.provider.MediaStore.Downloads.DATE_MODIFIED} DESC"

                    app.contentResolver.query(
                        android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads._ID)
                        val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.SIZE)
                        val dateCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                            val name = if (nameCol >= 0) cursor.getString(nameCol) else continue
                            if (name.isNullOrBlank()) continue
                            val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            val date = if (dateCol >= 0) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                            val contentUri = Uri.withAppendedPath(
                                android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                                id.toString()
                            )
                            val itemId = "file_$id"

                            if (!currentNames.contains(name) && !currentIds.contains(itemId)) {
                                currentNames.add(name)
                                currentIds.add(itemId)
                                historyStore.addEntry(
                                    com.famage.skydrop.history.TransferHistoryItem(
                                        id = itemId,
                                        timestamp = date,
                                        direction = "inbound",
                                        itemType = "file",
                                        name = name,
                                        size = size,
                                        peerName = "SkyDrop Drop",
                                        status = "completed",
                                        uri = contentUri.toString(),
                                        mimeType = mime
                                    )
                                )
                            }
                        }
                    }
                }

                // Also scan physical Downloads/SkyDrop directory for any files present on disk
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val skyDropFolder = java.io.File(downloadsDir, "SkyDrop")
                if (skyDropFolder.exists() && skyDropFolder.isDirectory) {
                    val diskFiles = skyDropFolder.listFiles()
                    if (diskFiles != null) {
                        for (file in diskFiles) {
                            if (file.isFile && !file.name.startsWith(".")) {
                                val fname = file.name
                                val fId = "disk_${file.lastModified()}_${fname.hashCode()}"
                                if (!currentNames.contains(fname)) {
                                    currentNames.add(fname)
                                    currentIds.add(fId)
                                    val ext = file.extension.lowercase()
                                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val fUri = Uri.fromFile(file)
                                    historyStore.addEntry(
                                        com.famage.skydrop.history.TransferHistoryItem(
                                            id = fId,
                                            timestamp = file.lastModified(),
                                            direction = "inbound",
                                            itemType = "file",
                                            name = fname,
                                            size = file.length(),
                                            peerName = "SkyDrop Drop",
                                            status = "completed",
                                            uri = fUri.toString(),
                                            mimeType = mime
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SendViewModel", "syncExistingDownloads warning: ${e.message}")
            }
        }
    }

    fun setSharedText(text: String?) {
        _sharedText.value = text
    }

    fun addManualDevice(host: String, port: Int, isTls: Boolean) {
        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(host)) {
            return
        }
        if (host.isNotBlank() && port in 1..65535) {
            discoveryManager.addManualDevice(host, port, "Manual Peer", isTls)
        }
    }

    fun isDevicePaired(device: Device): Boolean {
        val pairedList = pairedDeviceStore.pairedDevices.value
        return pairedList.any { paired ->
            (!device.fingerprint.isNullOrBlank() && paired.fingerprint.equals(device.fingerprint, ignoreCase = true)) ||
            paired.id == device.id ||
            paired.host == device.host ||
            paired.name.equals(device.name, ignoreCase = true)
        }
    }

    fun getPairedToken(device: Device): String? {
        val pairedList = pairedDeviceStore.pairedDevices.value
        return pairedList.firstOrNull { paired ->
            (!device.fingerprint.isNullOrBlank() && paired.fingerprint.equals(device.fingerprint, ignoreCase = true)) ||
            paired.id == device.id ||
            paired.host == device.host ||
            paired.name.equals(device.name, ignoreCase = true)
        }?.authToken
    }

    fun initiatePairingRequest(
        device: Device,
        onStatus: (String) -> Unit,
        onReadyForPin: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(device.host)) {
            onError("Cannot pair a device with itself")
            return
        }
        viewModelScope.launch {
            val localName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
            val localId = com.famage.skydrop.identity.DeviceIdentity.getDeviceId(getApplication())
            onStatus("Waiting for approval on ${device.name}...")
            val res = pairingManager.requestPairing(
                host = device.host,
                port = device.port,
                isTls = device.isTls,
                localDeviceName = localName,
                localDeviceId = localId,
                expectedFingerprint = device.fingerprint,
                onStatusUpdate = onStatus
            )
            if (res.isSuccess) {
                onReadyForPin()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Pairing request failed")
            }
        }
    }

    fun acceptIncomingPairRequest(
        req: com.famage.skydrop.model.IncomingPairRequest,
        onAccepted: (com.famage.skydrop.security.PairingManager.LocalPairingSession) -> Unit
    ) {
        val server = com.famage.skydrop.receiver.ReceiverService.activeServer
        val res = server?.acceptPairRequest(req.requestId)
        if (res != null) {
            onAccepted(res.second)
        }
    }

    fun declineIncomingPairRequest(req: com.famage.skydrop.model.IncomingPairRequest) {
        val server = com.famage.skydrop.receiver.ReceiverService.activeServer
        server?.declinePairRequest(req.requestId)
    }

    fun pairWithSecret(device: Device, secret: String, onResult: (Boolean, String?) -> Unit) {
        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(device.host)) {
            onResult(false, "Cannot pair a device with itself")
            return
        }
        viewModelScope.launch {
            val localName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
            val result = pairingManager.pairWithDevice(
                host = device.host,
                port = device.port,
                secret = secret.trim(),
                expectedFingerprint = device.fingerprint,
                localDeviceName = localName,
                isTls = device.isTls
            )

            if (result.isSuccess) {
                val paired = result.getOrNull()!!
                discoveryManager.addManualDevice(paired.host, paired.port, paired.name, paired.isTls)
                presenceManager.onDiscoveredEndpoint(paired.id, paired.name, paired.host, paired.port, paired.isTls)
                presenceManager.retryPeer(paired.id)
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "Pairing failed")
            }
        }
    }

    fun pairWithUri(rawUri: String, onResult: (Boolean, String?) -> Unit) {
        val data = PairingManager.parsePairingUri(rawUri)
        if (data == null) {
            onResult(false, "Invalid pairing QR code or URI")
            return
        }

        val targetHost = data.host.ifBlank {
            discoveryManager.devices.value.firstOrNull { it.name == data.deviceName }?.host ?: "127.0.0.1"
        }

        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(targetHost)) {
            onResult(false, "Cannot pair a device with itself")
            return
        }

        viewModelScope.launch {
            val localName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
            val result = pairingManager.pairWithDevice(
                host = targetHost,
                port = data.port,
                secret = data.secret,
                expectedFingerprint = data.fingerprint,
                localDeviceName = localName
            )

            if (result.isSuccess) {
                val paired = result.getOrNull()!!
                discoveryManager.addManualDevice(paired.host, paired.port, paired.name, paired.isTls)
                presenceManager.onDiscoveredEndpoint(paired.id, paired.name, paired.host, paired.port, paired.isTls)
                presenceManager.retryPeer(paired.id)
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "Pairing failed")
            }
        }
    }

    fun sendToDevice(device: Device) {
        sendToDevices(listOf(device))
    }

    fun sendToDevices(devices: List<Device>) {
        val validDevices = devices.filterNot { com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(it.host) }
        if (validDevices.isEmpty()) {
            _transferState.value = TransferState.Error("Cannot send files to the same device")
            return
        }

        val text = _sharedText.value
        val items = _queuedItems.value.toList()

        if (!text.isNullOrBlank()) {
            sendTextToDevices(validDevices, text)
            return
        }

        if (items.isEmpty()) return

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("SendViewModel", "Uncaught exception in sendToDevices: ${throwable.message}", throwable)
            _transferState.value = TransferState.Error(throwable.message ?: "Transfer exception occurred")
        }

        viewModelScope.launch(exceptionHandler) {
            _transferState.value = TransferState.Preparing
            val totalTargets = validDevices.size
            val totalFiles = items.size
            var totalTransfersSuccess = 0
            val failedTargets = mutableListOf<String>()

            for ((devIndex, device) in validDevices.withIndex()) {
                val token = getPairedToken(device)
                val targetPrefix = if (totalTargets > 1) "[Device ${devIndex + 1}/$totalTargets: ${device.name}] " else ""
                var deviceHadFailure = false

                for ((fileIndex, item) in items.withIndex()) {
                    val result = fileUploader.uploadFile(device, item, token) { bytesWritten, totalBytes, percent ->
                        _transferState.value = TransferState.Uploading(
                            currentFileName = "$targetPrefix[${fileIndex + 1}/$totalFiles] ${item.name}",
                            bytesTransferred = bytesWritten,
                            totalBytes = totalBytes,
                            progressPercent = percent
                        )
                    }

                    if (result.isFailure) {
                        val err = result.exceptionOrNull()?.message ?: "Unknown transfer failure"
                        deviceHadFailure = true
                        historyStore.addEntry(
                            com.famage.skydrop.history.TransferHistoryItem(
                                direction = "outbound",
                                itemType = "file",
                                name = item.name,
                                size = item.size,
                                peerName = device.name,
                                status = "failed",
                                errorMessage = err
                            )
                        )
                    } else {
                        totalTransfersSuccess++
                        historyStore.addEntry(
                            com.famage.skydrop.history.TransferHistoryItem(
                                direction = "outbound",
                                itemType = "file",
                                name = item.name,
                                size = item.size,
                                peerName = device.name,
                                status = "completed"
                            )
                        )
                    }
                }

                if (deviceHadFailure) {
                    failedTargets.add(device.name)
                }
            }

            clearQueuedItems()

            if (failedTargets.isEmpty()) {
                val successMsg = if (totalTargets > 1) {
                    "Successfully transferred $totalFiles file(s) to all $totalTargets devices!"
                } else {
                    "Successfully transferred $totalFiles file(s) to ${validDevices.first().name}"
                }
                _transferState.value = TransferState.Success(successMsg)
            } else if (failedTargets.size == totalTargets) {
                _transferState.value = TransferState.Error("Failed to transfer files to: ${failedTargets.joinToString()}")
            } else {
                _transferState.value = TransferState.Success("Transferred to ${totalTargets - failedTargets.size}/$totalTargets devices (failed on: ${failedTargets.joinToString()})")
            }
        }
    }

    private fun sendTextToDevices(devices: List<Device>, text: String) {
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("SendViewModel", "Uncaught exception in sendTextToDevices: ${throwable.message}", throwable)
            _transferState.value = TransferState.Error(throwable.message ?: "Text drop exception occurred")
        }

        viewModelScope.launch(exceptionHandler) {
            _transferState.value = TransferState.Preparing
            var successCount = 0
            val failedTargets = mutableListOf<String>()

            for (device in devices) {
                val token = getPairedToken(device)
                val result = fileUploader.sendText(device, text, token)
                if (result.isSuccess) {
                    successCount++
                    historyStore.addEntry(
                        com.famage.skydrop.history.TransferHistoryItem(
                            direction = "outbound",
                            itemType = "text",
                            name = text,
                            size = text.toByteArray().size.toLong(),
                            peerName = device.name,
                            status = "completed"
                        )
                    )
                } else {
                    failedTargets.add(device.name)
                    val err = result.exceptionOrNull()?.message ?: "Failed to send text"
                    historyStore.addEntry(
                        com.famage.skydrop.history.TransferHistoryItem(
                            direction = "outbound",
                            itemType = "text",
                            name = text,
                            size = text.toByteArray().size.toLong(),
                            peerName = device.name,
                            status = "failed",
                            errorMessage = err
                        )
                    )
                }
            }

            _sharedText.value = null

            if (failedTargets.isEmpty()) {
                val msg = if (devices.size > 1) {
                    "Text shared successfully with all ${devices.size} devices!"
                } else {
                    "Text shared successfully with ${devices.first().name}"
                }
                _transferState.value = TransferState.Success(msg)
            } else if (failedTargets.size == devices.size) {
                _transferState.value = TransferState.Error("Failed to send text to: ${failedTargets.joinToString()}")
            } else {
                _transferState.value = TransferState.Success("Text shared with $successCount/${devices.size} devices (failed on: ${failedTargets.joinToString()})")
            }
        }
    }

    fun resetTransferState() {
        _transferState.value = TransferState.Idle
    }

    fun retryPeer(peerId: String) {
        presenceManager.retryPeer(peerId)
    }

    fun getDiagnosticsReport(): String {
        return presenceManager.exportDiagnostics()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
    }

    private fun resolveSharedItem(resolver: ContentResolver, uri: Uri): SharedItem? {
        var name = "unknown_file"
        var size = 0L

        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            name = uri.lastPathSegment ?: "unknown_file"
        }

        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        return SharedItem(uri = uri, name = name, mimeType = mimeType, size = size)
    }
}
