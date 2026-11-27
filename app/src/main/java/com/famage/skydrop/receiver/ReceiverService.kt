package com.famage.skydrop.receiver

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.famage.skydrop.MainActivity
import com.famage.skydrop.presence.PresenceManager
import com.famage.skydrop.security.PairedDeviceStore

data class ReceiveProgress(
    val filename: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percent: Int
)

class ReceiverService : Service() {

    companion object {
        private const val TAG_SERVICE = "[SkyDropService]"
        private const val TAG_BATTERY = "[SkyDropBattery]"
        private const val TAG_NET = "[SkyDropNetwork]"
        private const val TAG_CLIP = "[SkyDropClipboard]"

        const val CHANNEL_ID = "skydrop_receiver_channel_silent"
        const val CHANNEL_INCOMING_ID = "skydrop_incoming_files"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.famage.skydrop.action.STOP_RECEIVER"
        const val DEFAULT_PORT = 8444

        val activeReceiveProgress = kotlinx.coroutines.flow.MutableStateFlow<ReceiveProgress?>(null)
        val activeIncomingRequests = kotlinx.coroutines.flow.MutableStateFlow<List<com.famage.skydrop.model.IncomingPairRequest>>(emptyList())
        var activePresenceManager: PresenceManager? = null
        var activeServer: EmbeddedReceiverServer? = null

        fun startService(context: Context) {
            try {
                val intent = Intent(context, ReceiverService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG_SERVICE, "startService invoked (Foreground start)")
            } catch (e: Exception) {
                Log.e(TAG_SERVICE, "Failed to start ReceiverService: ${e.message}", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, ReceiverService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                Log.i(TAG_SERVICE, "stopService requested")
            } catch (e: Exception) {
                Log.w(TAG_SERVICE, "Failed to stop ReceiverService: ${e.message}")
            }
        }
    }

    private var server: EmbeddedReceiverServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_SERVICE, "ReceiverService onCreate()")
        createNotificationChannels()
        // Immediately promote to foreground to comply with Android 14+ 5s SLA
        try {
            startForeground(NOTIFICATION_ID, buildPersistentNotification(DEFAULT_PORT))
        } catch (e: Exception) {
            Log.w(TAG_SERVICE, "Failed initial startForeground call: ${e.message}")
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG_SERVICE, "Received ACTION_STOP - shutting down ReceiverService")
            stopSelf()
            return START_NOT_STICKY
        }

        // Check power optimization status
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else {
            true
        }
        Log.i(TAG_BATTERY, "PowerManager.isIgnoringBatteryOptimizations: $isExempt")

        try {
            startForeground(NOTIFICATION_ID, buildPersistentNotification(DEFAULT_PORT))
        } catch (e: Exception) {
            Log.w(TAG_SERVICE, "startForeground error in onStartCommand: ${e.message}")
        }

        startServerAndDiscovery()
        return START_STICKY
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("SkyDrop:MulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG_NET, "WifiManager.MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG_NET, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            multicastLock = null
            Log.d(TAG_NET, "MulticastLock released")
        } catch (e: Exception) {
            Log.w(TAG_NET, "Error releasing MulticastLock: ${e.message}")
        }
    }

    /**
     * Acquire a short, transient 10-second WakeLock ONLY during active file uploads, then auto-release.
     * Never holds a permanent WakeLock.
     */
    fun acquireTransientWakeLock(durationMs: Long = 10000L) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SkyDrop:TransientTransferLock")
                wl.acquire(durationMs)
                Log.d(TAG_BATTERY, "Transient WakeLock acquired for ${durationMs}ms")
            }
        } catch (e: Exception) {
            Log.w(TAG_BATTERY, "Failed to acquire transient WakeLock: ${e.message}")
        }
    }

    private fun startServerAndDiscovery() {
        if (server != null) {
            Log.d(TAG_SERVICE, "Server already initialized on port $DEFAULT_PORT")
            return
        }

        acquireMulticastLock()

        try {
            val storageManager = ScopedStorageManager(applicationContext)
            val pairedStore = PairedDeviceStore(applicationContext)
            val clipboardStore = com.famage.skydrop.clipboard.ClipboardStore.getInstance(applicationContext)
            val deviceId = com.famage.skydrop.identity.DeviceIdentity.getDeviceId(applicationContext)

            server = EmbeddedReceiverServer(
                port = DEFAULT_PORT,
                storageManager = storageManager,
                pairedDeviceStore = pairedStore,
                deviceId = deviceId,
                onFileReceived = { filename, uri ->
                    showFileReceivedNotification(filename, uri)
                },
                onTextReceived = { text ->
                    showTextReceivedNotification(text)
                },
                onReceiveProgress = { filename, bytesRead, totalBytes, percent ->
                    activeReceiveProgress.value = ReceiveProgress(filename, bytesRead, totalBytes, percent)
                    acquireTransientWakeLock(5000L)
                },
                clipboardStore = clipboardStore,
                onClipboardReceived = { item ->
                    showClipboardReceivedNotification(item)
                }
            )

            server?.start()
            activeServer = server
            Log.i(TAG_SERVICE, "EmbeddedReceiverServer started on port $DEFAULT_PORT")

            // Initialize background presence manager
            if (activePresenceManager == null) {
                activePresenceManager = PresenceManager(applicationContext, pairedStore).apply {
                    start()
                }
            }

            registerMdns(DEFAULT_PORT)
        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "Failed to start EmbeddedReceiverServer: ${e.message}", e)
        }
    }

    private fun registerMdns(port: Int) {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) return

            val deviceName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
            val deviceId = com.famage.skydrop.identity.DeviceIdentity.getDeviceId(applicationContext)
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_filedrop._tcp"
                setPort(port)
                setAttribute("tls", "0")
                setAttribute("id", deviceId)
                setAttribute("device_id", deviceId)
                setAttribute("os", "android")
                setAttribute("version", "1.0.0")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG_SERVICE, "mDNS service registered: ${NsdServiceInfo.serviceName} on port $port")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG_SERVICE, "mDNS registration failed: errorCode $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.i(TAG_SERVICE, "mDNS service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG_SERVICE, "mDNS unregistration failed: errorCode $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG_SERVICE, "mDNS registration exception: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) return

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG_NET, "Network available - re-checking presence and discovery")
                    activePresenceManager?.start()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG_NET, "Network lost - pausing active presence probes")
                    activePresenceManager?.stop()
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG_NET, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (e: Exception) {
            Log.w(TAG_NET, "Failed to unregister NetworkCallback: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val silentChannel = NotificationChannel(
                CHANNEL_ID,
                "SkyDrop Background Sync Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent foreground notification for background presence & sync"
                setShowBadge(false)
            }

            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING_ID,
                "SkyDrop Incoming Drops",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when files or links are dropped to this device"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }

            nm.createNotificationChannel(silentChannel)
            nm.createNotificationChannel(incomingChannel)
        }
    }

    private fun buildPersistentNotification(port: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReceiverService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SkyDrop Connected")
            .setContentText("Listening on port $port")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun showFileReceivedNotification(filename: String, uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 10000).toInt(),
            viewIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_ID)
            .setContentTitle("File Received")
            .setContentText(filename)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    private fun showTextReceivedNotification(text: String) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 10000).toInt(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Copy action intent
        val copyIntent = Intent(this, CopyClipboardReceiver::class.java).apply {
            action = CopyClipboardReceiver.ACTION_COPY_CLIPBOARD
            putExtra(CopyClipboardReceiver.EXTRA_TEXT, text)
            putExtra(CopyClipboardReceiver.EXTRA_LABEL, "SkyDrop Text")
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            this,
            (System.currentTimeMillis() % 10000).toInt() + 1,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val preview = if (text.length > 100) text.take(100) + "…" else text
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_ID)
            .setContentTitle("Text Received")
            .setContentText(preview)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_send, "Copy", copyPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    private fun showClipboardReceivedNotification(item: com.famage.skydrop.clipboard.ClipboardItem) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 10000).toInt(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Copy action intent for notification button
        val copyIntent = Intent(this, CopyClipboardReceiver::class.java).apply {
            action = CopyClipboardReceiver.ACTION_COPY_CLIPBOARD
            putExtra(CopyClipboardReceiver.EXTRA_TEXT, item.text)
            putExtra(CopyClipboardReceiver.EXTRA_LABEL, "SkyDrop Clipboard from ${item.sourceDevice}")
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            this,
            (System.currentTimeMillis() % 10000).toInt() + 2,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val preview = if (item.text.length > 80) item.text.take(80) + "…" else item.text
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_ID)
            .setContentTitle("Clipboard item from ${item.sourceDevice}")
            .setContentText(preview)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_send, "Copy to Clipboard", copyPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG_SERVICE, "ReceiverService onDestroy()")
        unregisterNetworkCallback()
        activePresenceManager?.sendLeaveToAll()
        activePresenceManager?.stop()
        activePresenceManager = null

        releaseLocks()
        try {
            server?.stop()
            server = null
        } catch (e: Exception) {
            Log.w(TAG_SERVICE, "Error stopping server: ${e.message}")
        }

        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (_: Exception) {}
        }
        registrationListener = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG_SERVICE, "ReceiverService onTaskRemoved()")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
