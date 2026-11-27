package com.famage.skydrop

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Uncaught Exception Handler for SkyDrop Android.
 * Catches all unexpected crashes across background and UI threads, logs
 * detailed diagnostic stack traces to a local crash_logs.txt file, and logs to logcat.
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "[SkyDropCrash]"
        private const val CRASH_LOG_FILENAME = "crash_logs.txt"
        private const val MAX_LOG_SIZE_BYTES = 512 * 1024L // 512 KB log cap

        @Volatile
        private var instance: CrashHandler? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(CrashHandler::class.java) {
                    if (instance == null) {
                        val handler = CrashHandler(context.applicationContext)
                        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                        handler.defaultHandler = defaultHandler
                        Thread.setDefaultUncaughtExceptionHandler(handler)
                        instance = handler
                        Log.i(TAG, "Global CrashHandler initialized")
                    }
                }
            }
        }
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTraceStr = getStackTraceString(throwable)
        Log.e(TAG, "Uncaught Exception on thread '${thread.name}': ${throwable.message}", throwable)

        // Record crash details to local log file
        saveCrashReportToFile(thread.name, throwable, stackTraceStr)

        // Delegate to system default handler
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashReportToFile(threadName: String, throwable: Throwable, stackTraceStr: String) {
        try {
            val logFile = File(context.filesDir, CRASH_LOG_FILENAME)

            // Truncate log file if it exceeds size limit
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                logFile.delete()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val report = StringBuilder().apply {
                append("=== SKYDROP CRASH REPORT ===\n")
                append("Timestamp: $timestamp\n")
                append("Thread: $threadName\n")
                append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android API ${Build.VERSION.SDK_INT})\n")
                append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                append("Stack Trace:\n$stackTraceStr\n")
                append("============================\n\n")
            }.toString()

            FileWriter(logFile, true).use { writer ->
                writer.write(report)
            }
            Log.i(TAG, "Crash report successfully written to ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report to file: ${e.message}", e)
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
