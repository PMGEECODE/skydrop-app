package com.famage.skydrop.receiver

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.famage.skydrop.MainActivity
import com.famage.skydrop.security.PairedDeviceStore

data class ReceiveProgress(
    val filename: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percent: Int
)

class ReceiverService : Service() {

    companion object {
        private const val TAG = "ReceiverService"
        const val CHANNEL_ID = "skydrop_receiver_channel_silent"
        const val CHANNEL_INCOMING_ID = "skydrop_incoming_files"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.famage.skydrop.action.STOP_RECEIVER"
        const val DEFAULT_PORT = 8444

        val activeReceiveProgress = kotlinx.coroutines.flow.MutableStateFlow<ReceiveProgress?>(null)
        var activePresenceManager: com.famage.skydrop.presence.PresenceManager? = null

        fun startService(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: EmbeddedReceiverServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildPersistentNotification(DEFAULT_PORT)
        startForeground(NOTIFICATION_ID, notification)

        startServerAndDiscovery()

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("SkyDrop:MulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "WifiManager.MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }

        try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SkyDrop:ReceiverWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L) // 30 min maximum hold
                }
                Log.d(TAG, "PowerManager.WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        } finally {
            multicastLock = null
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun startServerAndDiscovery() {
        if (server != null) return

        acquireLocks()

        val storageManager = ScopedStorageManager(applicationContext)
        val pairedStore = PairedDeviceStore(applicationContext)
        val historyStore = com.famage.skydrop.history.TransferHistoryStore.getInstance(applicationContext)

        val myDeviceId = com.famage.skydrop.identity.DeviceIdentity.getDeviceId(applicationContext)
        val embedded = EmbeddedReceiverServer(
            port = DEFAULT_PORT,
            storageManager = storageManager,
            pairedDeviceStore = pairedStore,
            deviceId = myDeviceId,
            onFileReceived = { filename, uri ->
                showFileReceivedNotification(filename, uri)
            },
            onTextReceived = { text ->
                activeReceiveProgress.value = null
                historyStore.addEntry(
                    com.famage.skydrop.history.TransferHistoryItem(
                        direction = "inbound",
                        itemType = "text",
                        name = text,
                        size = text.toByteArray().size.toLong(),
                        peerName = "Peer Device",
                        status = "completed"
                    )
                )
                showTextReceivedNotification(text)
            },
            onReceiveProgress = { filename, bytesRead, totalBytes, percent ->
                activeReceiveProgress.value = ReceiveProgress(filename, bytesRead, totalBytes, percent)
            },
            onFileReceivedWithDetails = { filename, uri, size, mimeType ->
                activeReceiveProgress.value = null
                historyStore.addEntry(
                    com.famage.skydrop.history.TransferHistoryItem(
                        direction = "inbound",
                        itemType = "file",
                        name = filename,
                        size = size,
                        peerName = "Peer Device",
                        status = "completed",
                        uri = uri.toString(),
                        mimeType = mimeType
                    )
                )
            },
            onPeerLeft = { leavingId ->
                activePresenceManager?.onFastOfflineHint(leavingId)
            },
            clipboardStore = com.famage.skydrop.clipboard.ClipboardStore.getInstance(applicationContext),
            onClipboardReceived = { item ->
                Log.d(TAG, "Clipboard item received from ${item.sourceDevice}: ${item.id}")
            }
        )

        val actualPort = embedded.start()
        server = embedded

        // Initialize and start PresenceManager
        val presManager = com.famage.skydrop.presence.PresenceManager(applicationContext, pairedStore).apply {
            isTransferring = { _ -> embedded.isTransferring }
            onNetworkChangedListener = {
                advertiseService(actualPort)
            }
        }
        presManager.start()
        activePresenceManager = presManager

        // Update notification with actual port
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildPersistentNotification(actualPort))

        advertiseService(actualPort)
    }

    private fun advertiseService(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager

        // Safely unregister any existing registration before re-advertising
        registrationListener?.let { prevListener ->
            try {
                nsdManager?.unregisterService(prevListener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister previous mDNS service", e)
            }
            registrationListener = null
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = com.famage.skydrop.identity.DeviceIdentity.getDeviceName()
            serviceType = "_filedrop._tcp."
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("version", "1.0.0")
                setAttribute("tls", "false")
                setAttribute("os", "android")
                setAttribute("device_id", com.famage.skydrop.identity.DeviceIdentity.getDeviceId(applicationContext))
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Android receiver advertised via mDNS: ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS advertisement failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "mDNS service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }
        }
        registrationListener = listener

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating mDNS registration", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Silent, minimized background receiver channel
            val silentChannel = NotificationChannel(
                CHANNEL_ID,
                "SkyDrop Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Runs silent background file drop server"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(silentChannel)

            // High-priority alert channel for incoming file drops
            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING_ID,
                "SkyDrop Incoming Files",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when files or links are dropped to this device"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
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
            .setContentTitle("SkyDrop Active")
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
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_ID)
            .setContentTitle("Text Received")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        activePresenceManager?.sendLeaveToAll()
        activePresenceManager?.stop()
        activePresenceManager = null

        releaseLocks()
        server?.stop()
        server = null

        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (_: Exception) {}
        }
        registrationListener = null
        Log.i(TAG, "ReceiverService destroyed and stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
