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
    private val onPeerLeft: ((deviceId: String) -> Unit)? = null
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
        if (serverSocket != null) return serverSocket!!.localPort

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
        client.use { sock ->
            sock.soTimeout = 30000 // 30 sec header timeout
            val rawInput = sock.getInputStream()
            val output = sock.getOutputStream()

            // Read HTTP request line and headers manually without consuming the body
            val headerLines = mutableListOf<String>()
            val headerBuilder = StringBuilder()
            var prev = -1
            var curr: Int

            while (rawInput.read().also { curr = it } != -1) {
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
                method == "POST" && path == "/presence/leave" -> {
                    handlePresenceLeave(headers, rawInput, output)
                }
                method == "POST" && path == "/upload" -> {
                    handleUpload(headers, rawInput, output)
                }
                method == "POST" && path == "/text" -> {
                    handleText(headers, rawInput, output)
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "{\"error\":\"not_found\"}")
                }
            }
        }
    }

    private fun handleHealth(headers: Map<String, String>, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

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
