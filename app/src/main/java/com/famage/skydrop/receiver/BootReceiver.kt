package com.famage.skydrop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i(TAG, "Device boot/update detected - starting SkyDrop ReceiverService in background")
            try {
                ReceiverService.startService(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ReceiverService on boot", e)
            }
        }
    }
}
