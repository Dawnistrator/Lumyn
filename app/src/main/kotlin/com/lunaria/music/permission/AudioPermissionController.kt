package com.lunaria.music.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lunaria.core.mediascanner.permission.hasAudioReadPermission
import com.lunaria.core.mediascanner.permission.requiredAudioPermissionForApi
import com.lunaria.core.model.AudioPermissionStatus

@Stable
class AudioPermissionController(
    val status: AudioPermissionStatus,
    val onAction: () -> Unit,
)

/**
 * Three-state audio permission flow:
 * granted -> nothing to do; denied -> explain (when the system asks us to) then request;
 * permanently denied -> send the user to the app's system settings.
 * The grant state is re-read from the system on every resume and after every result.
 */
@Composable
fun rememberAudioPermissionController(): AudioPermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val permission = remember { requiredAudioPermissionForApi() }

    var granted by remember { mutableStateOf(hasAudioReadPermission(context)) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasAudioReadPermission(context)
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            permanentlyDenied = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasAudioReadPermission(context)
        if (granted) permanentlyDenied = false
    }

    val status = when {
        granted -> AudioPermissionStatus.Granted
        permanentlyDenied -> AudioPermissionStatus.PermanentlyDenied
        else -> AudioPermissionStatus.Denied
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Allow access to audio files") },
            text = {
                Text(
                    "Lunaria needs this permission to read music stored on your device. " +
                        "Everything stays offline and no data is ever sent.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launcher.launch(permission)
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Not now") }
            },
        )
    }

    return remember(status, activity, permission) {
        AudioPermissionController(status = status) {
            when (status) {
                AudioPermissionStatus.Granted -> Unit
                AudioPermissionStatus.PermanentlyDenied -> context.openAppSettings()
                AudioPermissionStatus.Denied ->
                    if (activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                    ) {
                        showRationale = true
                    } else {
                        launcher.launch(permission)
                    }
            }
        }
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
