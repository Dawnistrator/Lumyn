package com.lumyn.core.mediascanner

import android.Manifest
import com.lumyn.core.mediascanner.permission.isNotificationPermissionRequired
import com.lumyn.core.mediascanner.permission.requiredAudioPermissionForApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStoragePermissionTest {

    @Test
    fun api26To32UseLegacyReadExternalStorage() {
        listOf(26, 28, 29, 30, 31, 32).forEach {
            assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, requiredAudioPermissionForApi(it))
        }
    }

    @Test
    fun api33AndAboveUseReadMediaAudio() {
        listOf(33, 34, 35, 36).forEach {
            assertEquals(Manifest.permission.READ_MEDIA_AUDIO, requiredAudioPermissionForApi(it))
        }
    }

    @Test
    fun notificationPermissionIsRuntimeOnlyFromApi33() {
        assertFalse(isNotificationPermissionRequired(32))
        assertTrue(isNotificationPermissionRequired(33))
        assertTrue(isNotificationPermissionRequired(36))
    }
}
