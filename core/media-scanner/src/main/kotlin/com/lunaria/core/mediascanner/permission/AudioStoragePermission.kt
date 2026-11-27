package com.lunaria.core.mediascanner.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The storage permission that grants read access to audio files on a given Android version. */
fun requiredAudioPermissionForApi(sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** Notification permission only exists as a runtime permission from Android 13. */
fun isNotificationPermissionRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

/** Always queries the system; the result is intentionally never cached. */
fun hasAudioReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, requiredAudioPermissionForApi()) ==
        PackageManager.PERMISSION_GRANTED

fun hasNotificationPermission(context: Context): Boolean =
    !isNotificationPermissionRequired() ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
