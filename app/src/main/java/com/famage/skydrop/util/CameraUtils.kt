package com.famage.skydrop.util

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager

/**
 * Utility for detecting usable camera hardware on the current device.
 * Correctly distinguishes between phones/tablets with real camera sensors
 * and Android TVs / headless devices that lack physical cameras.
 */
object CameraUtils {
    /**
     * Checks if the device has at least one physically usable camera.
     * On Android TV or devices without cameras, CameraManager returns an empty list,
     * whereas phones and tablets return one or more camera IDs.
     */
    fun hasUsableCamera(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraIds = cameraManager?.cameraIdList
            if (cameraIds != null) {
                cameraIds.isNotEmpty()
            } else {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            }
        } catch (_: Exception) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        }
    }
}
