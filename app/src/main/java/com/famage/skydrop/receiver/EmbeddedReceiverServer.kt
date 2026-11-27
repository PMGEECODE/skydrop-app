package com.famage.skydrop.receiver

import android.net.Uri
import android.os.Build
import android.util.Log
import com.famage.skydrop.security.PairedDeviceStore
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class EmbeddedReceiverServer(
    val port: Int,
    private val storageManager: ScopedStorageManager,
    private val pairedDeviceStore: PairedDeviceStore,
    private val deviceId: String = "android-device",
    private val onFileReceived: (filename: String, uri: Uri) -> Unit,
    private val onTextReceived: (text: String) -> Unit,
    private val onReceiveProgress: ((filename: String, bytesRead: Long, totalBytes: Long, percent: Int) -> Unit)? = null,
    private val onFileReceivedWithDetails: ((filename: String, uri: Uri, size: Long, mimeType: String) -> Unit)? = null,
    private val onPeerLeft: ((deviceId: String) -> Unit)? = null,
    private val clipboardStore: com.famage.skydrop.clipboard.ClipboardStore? = null,
    private val onClipboardReceived: ((com.famage.skydrop.clipboard.ClipboardItem) -> Unit)? = null
) {

    companion object {
        private const val TAG = "EmbeddedReceiverServer"
    }

    @Volatile
    var isTransferring: Boolean = false

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    fun start(): Int {
        if (serverSocket != null) return serverSocket?.localPort ?: port

        val socket = ServerSocket(port)
        serverSocket = socket
        val actualPort = socket.localPort

        serverJob = scope.launch {
            Log.i(TAG, "Embedded HTTP receiver started on port $actualPort")
            while (isActive) {
                try {
                    val client = socket.accept()
                    launch { handleConnection(client) }
                } catch (e: SocketException) {
                    // Socket closed on stop()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Accept error", e)
                }
            }
        }

        return actualPort
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private suspend fun handleConnection(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.use { sock ->
                sock.soTimeout = 30000 // 30 sec header timeout
                val rawInput = sock.getInputStream()
                val output = sock.getOutputStream()

                // Read HTTP request line and headers manually without consuming the body
                val headerLines = mutableListOf<String>()
                val headerBuilder = StringBuilder()
                var prev = -1
                var curr: Int
                var totalHeaderBytes = 0

                while (rawInput.read().also { curr = it } != -1) {
                    totalHeaderBytes++
                    if (totalHeaderBytes > 65536) {
                        Log.w(TAG, "HTTP header limit exceeded (64KB), closing socket")
                        return@use
                    }
                    headerBuilder.append(curr.toChar())
                    if (prev == '\r'.code && curr == '\n'.code) {
                        val line = headerBuilder.toString().trim()
                        headerBuilder.clear()
                        if (line.isEmpty()) {
                            // End of headers reached
                            break
                        }
                        headerLines.add(line)
                    }
                    prev = curr
                }

            if (headerLines.isEmpty()) return@withContext

            val requestLine = headerLines[0].split(" ")
            if (requestLine.size < 2) {
                sendResponse(output, 400, "Bad Request", "{\"error\":\"bad_request\"}")
                return@withContext
            }

            val method = requestLine[0]
            val path = requestLine[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until headerLines.size) {
                val parts = headerLines[i].split(":", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].trim().lowercase()] = parts[1].trim()
                }
            }

            when {
                method == "GET" && path == "/info" -> {
                    val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val json = "{\"device_name\":\"$deviceName\",\"version\":\"1.0.0\",\"os\":\"android\",\"protocol\":\"skydrop/v1\"}"
                    sendResponse(output, 200, "OK", json)
                }
                method == "GET" && path == "/health" -> {
                    handleHealth(headers, output)
                }
                method == "POST" && path == "/pair/request" -> {
                    handlePairRequest(sock, headers, rawInput, output)
                }
                method == "GET" && path.startsWith("/pair/request/status") -> {
                    handlePairRequestStatus(path, output)
                }
                method == "POST" && path == "/pair/request/cancel" -> {
                    handlePairRequestCancel(rawInput, output)
                }
                method == "POST" && path == "/pair/start" -> {
                    handlePairStart(output)
                }
                method == "POST" && path == "/pair/confirm" -> {
                    handlePairConfirm(sock, headers, rawInput, output)
                }
                method == "POST" && path == "/presence/leave" -> {
                    handlePresenceLeave(headers, rawInput, output)
                }
                method == "POST" && path == "/upload" -> {
                    handleUpload(headers, rawInput, output)
                }
                method == "POST" && path == "/text" -> {
                    handleText(headers, rawInput, output)
                }
                method == "POST" && path == "/clipboard/add" -> {
                    handleClipboardAdd(headers, rawInput, output)
                }
                method == "GET" && path == "/clipboard/sync" -> {
                    handleClipboardSync(headers, output)
                }
                method == "POST" && path == "/clipboard/push" -> {
                    handleClipboardPush(headers, rawInput, output)
                }
                method == "GET" && path == "/clipboard/list" -> {
                    handleClipboardList(headers, output)
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "{\"error\":\"not_found\"}")
                }
            }
        }
        } catch (e: Exception) {
            Log.w(TAG, "Connection handling exception: ${e.message}")
        }
    }

    private val pendingPairRequests = java.util.concurrent.ConcurrentHashMap<String, com.famage.skydrop.model.IncomingPairRequest>()

    fun getPendingPairRequests(): List<com.famage.skydrop.model.IncomingPairRequest> {
        val now = System.currentTimeMillis()
        return pendingPairRequests.values.filter { it.status == "pending" && it.expiresAtMs > now }
    }

    fun acceptPairRequest(requestId: String): Pair<com.famage.skydrop.model.IncomingPairRequest, com.famage.skydrop.security.PairingManager.LocalPairingSession>? {
        val req = pendingPairRequests[requestId] ?: return null
        if (System.currentTimeMillis() > req.expiresAtMs) {
            req.status = "expired"
            updateIncomingFlow()
            return null
        }
        req.status = "accepted"
        val pairingManager = com.famage.skydrop.security.PairingManager(pairedDeviceStore)
        val session = pairingManager.generateLocalSession()
        updateIncomingFlow()
        return Pair(req, session)
    }

    fun declinePairRequest(requestId: String): com.famage.skydrop.model.IncomingPairRequest? {
        val req = pendingPairRequests[requestId] ?: return null
        req.status = "declined"
        updateIncomingFlow()
        return req
    }

    fun cancelPairRequest(requestId: String): com.famage.skydrop.model.IncomingPairRequest? {
        val req = pendingPairRequests[requestId] ?: return null
        req.status = "cancelled"
        updateIncomingFlow()
        return req
    }

    private fun updateIncomingFlow() {
        ReceiverService.activeIncomingRequests.value = getPendingPairRequests()
    }

    private fun handlePairRequest(sock: Socket, headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val buf = ByteArray(4096)
        val readCount = input.read(buf)
        if (readCount <= 0) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Empty request body\"}")
            return
        }
        val bodyStr = String(buf, 0, readCount, Charsets.UTF_8)
        try {
            val json = org.json.JSONObject(bodyStr)
            val remoteName = json.optString("device_name", "Remote Peer")
            val rawReqId = json.optString("request_id")
            val reqId = if (rawReqId.isNullOrBlank()) java.util.UUID.randomUUID().toString() else rawReqId
            val rawDevId = json.optString("device_id")
            val devId = if (rawDevId.isNullOrBlank()) java.util.UUID.randomUUID().toString() else rawDevId
            val devType = json.optString("device_type", "desktop")
            val remoteIp = sock.inetAddress?.hostAddress ?: ""
            val host = json.optString("host", remoteIp)
            val port = json.optInt("port", 8443)
            val isTls = json.optBoolean("is_tls", port == 8443)
            val fp = json.optString("fingerprint", "")

            val request = com.famage.skydrop.model.IncomingPairRequest(
                requestId = reqId,
                deviceId = devId,
                deviceName = remoteName,
                deviceType = devType,
                host = host,
                port = port,
                isTls = isTls,
                fingerprint = fp,
                expiresAtMs = System.currentTimeMillis() + 90000L,
                status = "pending"
            )

            pendingPairRequests[reqId] = request
            updateIncomingFlow()

            val respJson = org.json.JSONObject().apply {
                put("status", "pending")
                put("request_id", reqId)
                put("expires_at", request.expiresAtMs.toString())
            }.toString()

            sendResponse(output, 200, "OK", respJson)
        } catch (e: Exception) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Failed to parse request JSON\"}")
        }
    }

    private fun handlePairRequestStatus(path: String, output: OutputStream) {
        val reqId = if (path.contains("request_id=")) {
            path.substringAfter("request_id=").substringBefore("&")
        } else ""

        val req = pendingPairRequests[reqId]
        if (req == null) {
            sendResponse(output, 404, "Not Found", "{\"error\":\"not_found\",\"message\":\"Request not found\"}")
            return
        }

        if (req.status == "pending" && System.currentTimeMillis() > req.expiresAtMs) {
            req.status = "expired"
            updateIncomingFlow()
        }

        val deviceName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
        val json = org.json.JSONObject().apply {
            put("request_id", req.requestId)
            put("status", req.status)
            put("device_name", deviceName)
        }.toString()

        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairRequestCancel(input: InputStream, output: OutputStream) {
        val buf = ByteArray(2048)
        val readCount = input.read(buf)
        var reqId = ""
        if (readCount > 0) {
            val bodyStr = String(buf, 0, readCount, Charsets.UTF_8)
            try {
                val json = org.json.JSONObject(bodyStr)
                reqId = json.optString("request_id")
            } catch (_: Exception) {}
        }
        val req = cancelPairRequest(reqId)
        val json = org.json.JSONObject().apply {
            put("status", req?.status ?: "cancelled")
            put("request_id", reqId)
        }.toString()
        sendResponse(output, 200, "OK", json)
    }

    private fun handleHealth(headers: Map<String, String>, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        val senderId = headers["x-skydrop-device-id"]
        if (!senderId.isNullOrBlank()) {
            ReceiverService.activePresenceManager?.recordActivity(senderId)
        }

        if (pairedDeviceStore.pairedDevices.value.isNotEmpty()) {
            val isAuthorized = pairedDeviceStore.pairedDevices.value.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"ERR_AUTH_FAILED\",\"message\":\"Device not paired or token invalid\"}")
                return
            }
        }

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val currentState = if (isTransferring) "transferring" else "idle"
        val json = """
            {
                "device_id": "$deviceId",
                "device_name": "$deviceName",
                "app_version": "1.0.0",
                "protocol_version": "skydrop/v1",
                "capabilities": ["health_v1", "resumable_upload", "checksum_v1", "batch_transfer"],
                "state": "$currentState"
            }
        """.trimIndent()
        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairStart(output: OutputStream) {
        val pairingManager = com.famage.skydrop.security.PairingManager(pairedDeviceStore)
        val session = pairingManager.generateLocalSession()
        
        val deviceName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
        // Determine local IP if possible, or fallback to an empty host (the client already knows where they connected)
        val hostIP = "" 
        
        // This mirrors the Go daemon's response format exactly
        val pairingURI = "skydrop://pair?device=${Uri.encode(deviceName)}&host=$hostIP&port=$port&secret=${session.secret}&fingerprint="
        
        val json = org.json.JSONObject().apply {
            put("status", "ready")
            put("device_name", deviceName)
            put("host", hostIP)
            put("fingerprint", "")
            put("port", port)
            put("secret", session.secret)
            put("pairing_uri", pairingURI)
            // Omitting qrcode base64 for now, Android client can render from URI
            put("expires_at", session.expiresAtMs.toString()) // Placeholder format
        }.toString()
        
        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairConfirm(sock: Socket, headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 4096
        val toRead = contentLength.coerceAtMost(65536)
        val buf = ByteArray(toRead)
        var totalRead = 0
        while (totalRead < toRead) {
            val count = input.read(buf, totalRead, toRead - totalRead)
            if (count == -1) break
            totalRead += count
        }
        if (totalRead <= 0) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Empty request body\"}")
            return
        }
        
        val bodyStr = String(buf, 0, totalRead, Charsets.UTF_8)
        try {
            val json = org.json.JSONObject(bodyStr)
            val secret = json.optString("secret")
            val remoteDeviceName = json.optString("device_name", "Remote Device")
            val remoteIp = sock.inetAddress?.hostAddress ?: ""
            val host = json.optString("host", remoteIp).ifBlank { remoteIp }
            val isTls = json.optBoolean("is_tls", false)
            val port = json.optInt("port", if (isTls) 8443 else 8444)
            val fp = json.optString("fingerprint", "")
            
            if (com.famage.skydrop.security.PairingManager.validateLocalSession(secret)) {
                val newAuthToken = java.util.UUID.randomUUID().toString() + "-" + java.util.UUID.randomUUID().toString()
                com.famage.skydrop.security.PairingManager.activeLocalSession?.authToken = newAuthToken
                
                val remoteDeviceId = json.optString("device_id", java.util.UUID.randomUUID().toString())
                
                val pairedDevice = com.famage.skydrop.model.PairedDevice(
                    id = remoteDeviceId,
                    name = remoteDeviceName,
                    host = host,
                    port = port,
                    isTls = isTls,
                    fingerprint = fp,
                    authToken = newAuthToken
                )
                
                pairedDeviceStore.saveDevice(pairedDevice)
                ReceiverService.activePresenceManager?.recordActivity(remoteDeviceId)
                com.famage.skydrop.security.PairingManager.activeLocalSession = null // Consume session
                com.famage.skydrop.security.PairingManager.notifyPairSuccess(pairedDevice)
                
                val deviceName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
                val responseJson = org.json.JSONObject().apply {
                    put("status", "paired")
                    put("auth_token", newAuthToken)
                    put("device_id", deviceId)
                    put("device_name", deviceName)
                    put("fingerprint", "")
                }.toString()
                
                sendResponse(output, 200, "OK", responseJson)
            } else {
                sendResponse(output, 403, "Forbidden", "{\"error\":\"pairing_failed\",\"message\":\"Invalid or expired secret\"}")
            }
        } catch (e: Exception) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Failed to parse pair confirmation JSON\"}")
        }
    }

    private fun handlePresenceLeave(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        if (pairedDeviceStore.pairedDevices.value.isNotEmpty()) {
            val isAuthorized = pairedDeviceStore.pairedDevices.value.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"ERR_AUTH_FAILED\"}")
                return
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0 && contentLength < 4096) {
            val buf = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = input.read(buf, readTotal, contentLength - readTotal)
                if (r == -1) break
                readTotal += r
            }
            try {
                val str = String(buf, 0, readTotal, Charsets.UTF_8)
                val json = org.json.JSONObject(str)
                val leavingId = json.optString("device_id")
                if (leavingId.isNotBlank()) {
                    onPeerLeft?.invoke(leavingId)
                }
            } catch (_: Exception) {}
        }

        sendResponse(output, 200, "OK", "{\"status\":\"ok\"}")
    }

    private fun handleUpload(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        // If paired devices exist, verify token
        if (pairedDeviceStore.pairedDevices.value.isNotEmpty()) {
            val isAuthorized = pairedDeviceStore.pairedDevices.value.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\",\"message\":\"Device not paired\"}")
                return
            }
        }

        val rawFilename = headers["x-filename"] ?: "incoming_file_${System.currentTimeMillis()}"
        val filename = try {
            java.net.URLDecoder.decode(rawFilename, "UTF-8")
        } catch (_: Exception) {
            rawFilename
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        // Wrap input with BoundedInputStream if content-length is declared
        val streamToRead = if (contentLength >= 0) BoundedInputStream(input, contentLength) else input

        isTransferring = true
        try {
            val result = storageManager.saveStreamWithResult(filename, streamToRead, contentLength) { written ->
                if (contentLength > 0) {
                    val pct = ((written * 100) / contentLength).toInt().coerceIn(0, 100)
                    onReceiveProgress?.invoke(filename, written, contentLength, pct)
                } else {
                    onReceiveProgress?.invoke(filename, written, -1L, -1)
                }
            }

            if (result.isSuccess) {
                val saveResult = result.getOrThrow()
                val senderId = headers["x-skydrop-device-id"]
                if (!senderId.isNullOrBlank()) {
                    ReceiverService.activePresenceManager?.recordActivity(senderId)
                }
                onFileReceived(filename, saveResult.uri)
                onFileReceivedWithDetails?.invoke(filename, saveResult.uri, saveResult.bytesWritten, saveResult.mimeType)
                sendResponse(output, 200, "OK", "{\"status\":\"success\",\"saved_uri\":\"${saveResult.uri}\"}")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Save failed"
                sendResponse(output, 500, "Internal Server Error", "{\"error\":\"save_failed\",\"message\":\"$err\"}")
            }
        } finally {
            isTransferring = false
        }
    }

    private fun handleText(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0 || contentLength > 1024 * 1024) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_payload_length\"}")
            return
        }

        val bytes = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(bytes, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }

        val body = String(bytes, Charsets.UTF_8)
        onTextReceived(body)
        sendResponse(output, 200, "OK", "{\"status\":\"success\"}")
    }

    private fun checkAuth(headers: Map<String, String>): Boolean {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()
        if (pairedDeviceStore.pairedDevices.value.isNotEmpty()) {
            return pairedDeviceStore.pairedDevices.value.any { it.authToken == token }
        }
        return true
    }

    private fun handleClipboardAdd(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        if (!checkAuth(headers)) {
            sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\",\"message\":\"Device not paired\"}")
            return
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0 || contentLength > 64 * 1024) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_payload_length\"}")
            return
        }

        val bytes = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(bytes, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }

        val body = String(bytes, Charsets.UTF_8)
        try {
            val json = org.json.JSONObject(body)
            val text = json.optString("text", "")
            if (text.isBlank()) {
                sendResponse(output, 400, "Bad Request", "{\"error\":\"empty_text\"}")
                return
            }
            val id = json.optString("id", java.util.UUID.randomUUID().toString())
            val sourceDevice = json.optString("source_device", "Remote Device")
            val originId = json.optString("origin_id", id)
            val pinned = json.optBoolean("pinned", false)

            val item = com.famage.skydrop.clipboard.ClipboardItem(
                id = id,
                text = text,
                sourceDevice = sourceDevice,
                createdAt = System.currentTimeMillis(),
                pinned = pinned,
                originId = originId
            )

            clipboardStore?.add(item)
            onClipboardReceived?.invoke(item)
            sendResponse(output, 200, "OK", "{\"status\":\"success\",\"id\":\"$id\"}")
        } catch (e: Exception) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\"}")
        }
    }

    private fun handleClipboardSync(headers: Map<String, String>, output: OutputStream) {
        if (!checkAuth(headers)) {
            sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\"}")
            return
        }

        val ids = clipboardStore?.getIds() ?: emptyList()
        val array = org.json.JSONArray()
        for (id in ids) {
            array.put(id)
        }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val json = org.json.JSONObject().apply {
            put("ids", array)
            put("device", deviceName)
        }.toString()

        sendResponse(output, 200, "OK", json)
    }

    private fun handleClipboardPush(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        if (!checkAuth(headers)) {
            sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\"}")
            return
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0 || contentLength > 1024 * 1024) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_payload_length\"}")
            return
        }

        val bytes = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(bytes, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }

        val body = String(bytes, Charsets.UTF_8)
        try {
            val json = org.json.JSONObject(body)
            val array = json.optJSONArray("items") ?: org.json.JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                val text = obj.optString("text", "")
                if (text.isBlank()) continue
                val source = obj.optString("source_device", "Remote Device")
                val originId = obj.optString("origin_id", id)
                val pinned = obj.optBoolean("pinned", false)

                val item = com.famage.skydrop.clipboard.ClipboardItem(
                    id = id,
                    text = text,
                    sourceDevice = source,
                    createdAt = System.currentTimeMillis(),
                    pinned = pinned,
                    originId = originId
                )
                clipboardStore?.add(item)
            }
            sendResponse(output, 200, "OK", "{\"status\":\"success\"}")
        } catch (_: Exception) {
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\"}")
        }
    }

    private fun handleClipboardList(headers: Map<String, String>, output: OutputStream) {
        if (!checkAuth(headers)) {
            sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\"}")
            return
        }

        val items = clipboardStore?.items?.value ?: emptyList()
        val array = org.json.JSONArray()
        for (item in items) {
            array.put(org.json.JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("source_device", item.sourceDevice)
                put("created_at", item.createdAt)
                put("pinned", item.pinned)
                put("origin_id", item.originId)
            })
        }
        val json = org.json.JSONObject().apply {
            put("items", array)
            put("count", items.size)
        }.toString()

        sendResponse(output, 200, "OK", json)
    }

    private fun sendResponse(output: OutputStream, code: Int, status: String, jsonBody: String) {
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private class BoundedInputStream(private val target: InputStream, private val limit: Long) : InputStream() {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val b = target.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val maxToRead = len.toLong().coerceAtMost(limit - bytesRead).toInt()
            val read = target.read(b, off, maxToRead)
            if (read != -1) bytesRead += read
            return read
        }
    }
}
