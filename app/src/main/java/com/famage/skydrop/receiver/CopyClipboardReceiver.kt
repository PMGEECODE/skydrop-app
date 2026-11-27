package com.famage.skydrop.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class CopyClipboardReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "[SkyDropClipboard]"
        const val ACTION_COPY_CLIPBOARD = "com.famage.skydrop.action.COPY_CLIPBOARD"
        const val EXTRA_TEXT = "extra_clipboard_text"
        const val EXTRA_LABEL = "extra_clipboard_label"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == ACTION_COPY_CLIPBOARD) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            val label = intent.getStringExtra(EXTRA_LABEL) ?: "SkyDrop Drop"

            if (!text.isNullOrBlank()) {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm != null) {
                        val clip = ClipData.newPlainText(label, text)
                        cm.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "Successfully copied text to system clipboard via user notification action (${text.length} chars)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy text to system clipboard: ${e.message}", e)
                    Toast.makeText(context, "Could not copy text: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
