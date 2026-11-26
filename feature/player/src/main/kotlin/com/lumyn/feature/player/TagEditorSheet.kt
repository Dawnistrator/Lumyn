package com.lumyn.feature.player

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.Track
import kotlinx.coroutines.launch

data class TagEditorSubmission(
    val track: Track,
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
    val year: Int,
    val genre: String,
    val pickedArtworkUri: String?,
    val writeConsentGranted: Boolean,
)

/**
 * Offline tag editor modal supporting zero-permission artwork picking (`PickVisualMedia`)
 * and Android 11+ `MediaStore.createWriteRequest()` system write consent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorSheet(
    track: Track,
    onSave: suspend (TagEditorSubmission) -> IntentSender?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artist by rememberSaveable(track.id) { mutableStateOf(track.artist) }
    var album by rememberSaveable(track.id) { mutableStateOf(track.album) }
    var trackNumberText by rememberSaveable(track.id) {
        mutableStateOf(if (track.trackNumber > 0) track.trackNumber.toString() else "")
    }
    var yearText by rememberSaveable(track.id) {
        mutableStateOf(if (track.year > 0) track.year.toString() else "")
    }
    var genre by rememberSaveable(track.id) { mutableStateOf(track.genre) }
    var pickedArtworkUri by rememberSaveable(track.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun currentSubmission(consentGranted: Boolean) = TagEditorSubmission(
        track = track,
        title = title,
        artist = artist,
        album = album,
        trackNumber = trackNumberText.trim().toIntOrNull() ?: 0,
        year = yearText.trim().toIntOrNull() ?: 0,
        genre = genre,
        pickedArtworkUri = pickedArtworkUri,
        writeConsentGranted = consentGranted,
    )

    val artworkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            pickedArtworkUri = uri.toString()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        scope.launch {
            saving = true
            onSave(currentSubmission(consentGranted = granted))
            saving = false
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit tags", style = MaterialTheme.typography.titleLarge)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LumynArtwork(
                    artworkUri = pickedArtworkUri ?: track.artworkUri,
                    contentDescription = track.album,
                    cornerRadius = 12.dp,
                    modifier = Modifier.size(72.dp),
                )
                OutlinedButton(
                    onClick = {
                        artworkPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !saving,
                ) {
                    Icon(
                        painter = painterResource(LumynIcons.Image),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Change artwork", modifier = Modifier.padding(start = 8.dp))
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                singleLine = true,
                label = { Text("Artist") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                singleLine = true,
                label = { Text("Album") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = trackNumberText,
                    onValueChange = { input -> trackNumberText = input.filter { it.isDigit() } },
                    singleLine = true,
                    label = { Text("Track #") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { input -> yearText = input.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text("Year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = genre,
                onValueChange = { genre = it },
                singleLine = true,
                label = { Text("Genre") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            val consentSender = onSave(currentSubmission(consentGranted = false))
                            if (consentSender != null) {
                                consentLauncher.launch(IntentSenderRequest.Builder(consentSender).build())
                            } else {
                                saving = false
                                onDismiss()
                            }
                        }
                    },
                    enabled = !saving && title.isNotBlank(),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }
        }
    }
}
