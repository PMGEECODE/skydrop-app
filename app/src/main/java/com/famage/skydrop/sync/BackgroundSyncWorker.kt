package com.famage.skydrop.sync

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famage.skydrop.clipboard.ClipboardStore
import com.famage.skydrop.clipboard.ClipboardSyncClient
import com.famage.skydrop.security.PairedDeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BackgroundSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "[SkyDropService]"
        private const val WORK_NAME = "skydrop_background_sync_periodic"

        fun schedule(context: Context) {
            try {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                    15, TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Log.d(TAG, "Periodic BackgroundSyncWorker scheduled")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule WorkManager periodic sync: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isExempt = pm?.isIgnoringBatteryOptimizations(applicationContext.packageName) ?: false
        Log.i(TAG, "BackgroundSyncWorker triggered (Battery optimization exempt: $isExempt)")

        return@withContext try {
            val store = ClipboardStore.getInstance(applicationContext)
            val pairedStore = PairedDeviceStore(applicationContext)
            val syncClient = ClipboardSyncClient()

            if (!store.syncEnabled.value) {
                return@withContext Result.success()
            }

            val peers = pairedStore.pairedDevices.value
            if (peers.isEmpty()) {
                return@withContext Result.success()
            }

            for (peer in peers) {
                val scheme = if (peer.isTls) "https" else "http"
                val baseUrl = "$scheme://${peer.host}:${peer.port}"

                val syncResult = syncClient.syncIds(baseUrl, peer.authToken)
                if (syncResult.isSuccess) {
                    val remoteIds = syncResult.getOrThrow()
                    val missing = store.getMissing(remoteIds)
                    if (missing.isNotEmpty()) {
                        syncClient.pushMissing(baseUrl, peer.authToken, missing)
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "BackgroundSyncWorker exception: ${e.message}")
            Result.retry()
        }
    }
}
