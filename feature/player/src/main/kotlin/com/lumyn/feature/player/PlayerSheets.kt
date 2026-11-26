package com.lumyn.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lumyn.core.audioengine.QueueOperations
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.formatDurationMs
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.SleepTimerState
import com.lumyn.core.model.Track
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    onSkipToIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    val queueEntries = remember(queue) {
        val counts = HashMap<Long, Int>(queue.size)
        queue.map { track ->
            val occ = (counts[track.id] ?: 0) + 1
            counts[track.id] = occ
            QueueSlot(key = "${track.id}#$occ", track = track)
        }
    }

    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val latestOnMoveItem by rememberUpdatedState(onMoveItem)

    if (showSaveDialog) {
        SaveQueuePlaylistDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                onSaveAsPlaylist(name)
                showSaveDialog = false
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Queue (${queue.size} ${if (queue.size == 1) "track" else "tracks"})",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (queue.isNotEmpty()) {
                    TextButton(onClick = { showSaveDialog = true }) {
                        Text("Save playlist", maxLines = 1, softWrap = false)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (queueEntries.isEmpty()) {
                Text(
                    text = "The queue is empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(
                        items = queueEntries,
                        key = { _, slot -> slot.key },
                        contentType = { _, _ -> "queue-item" },
                    ) { index, slot ->
                        val track = slot.track
                        val isCurrent = index == currentIndex
                        val isDragged = draggedKey == slot.key
                        val latestIndex by rememberUpdatedState(index)
                        var pendingMoveTargetIdx by remember(slot.key) { mutableStateOf<Int?>(null) }
                        LaunchedEffect(index) {
                            if (pendingMoveTargetIdx == index) {
                                pendingMoveTargetIdx = null
                            }
                        }
                        val bgColor = if (isCurrent) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else if (isDragged) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }

                        ListItem(
                            onClick = { onSkipToIndex(index) },
                            modifier = Modifier
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragged) dragOffsetY else 0f
                                },
                            colors = ListItemDefaults.colors(containerColor = bgColor),
                            leadingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 28.dp, minHeight = 48.dp)
                                            .pointerInput(slot.key) {
                                                detectVerticalDragGestures(
                                                    onDragStart = {
                                                        draggedKey = slot.key
                                                        dragOffsetY = 0f
                                                        pendingMoveTargetIdx = null
                                                    },
                                                    onDragEnd = {
                                                        draggedKey = null
                                                        dragOffsetY = 0f
                                                        pendingMoveTargetIdx = null
                                                    },
                                                    onDragCancel = {
                                                        draggedKey = null
                                                        dragOffsetY = 0f
                                                        pendingMoveTargetIdx = null
                                                    },
                                                    onVerticalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetY += dragAmount
                                                        val visible = listState.layoutInfo.visibleItemsInfo
                                                        val activeInfo = visible.firstOrNull { it.key == slot.key }
                                                        if (activeInfo != null &&
                                                            pendingMoveTargetIdx != null &&
                                                            pendingMoveTargetIdx != latestIndex &&
                                                            abs(dragOffsetY) > activeInfo.size * 1.5f
                                                        ) {
                                                            pendingMoveTargetIdx = null
                                                        }
                                                        if (pendingMoveTargetIdx != null && pendingMoveTargetIdx != latestIndex) {
                                                            return@detectVerticalDragGestures
                                                        }
                                                        val activeIdx = latestIndex
                                                        if (activeInfo != null && activeInfo.index == activeIdx) {
                                                            val center = activeInfo.offset +
                                                                (activeInfo.size / 2f) +
                                                                dragOffsetY
                                                            val nextInfo = visible.firstOrNull { it.index == activeIdx + 1 }
                                                            val prevInfo = visible.firstOrNull { it.index == activeIdx - 1 }
                                                            if (nextInfo != null &&
                                                                center > nextInfo.offset + (nextInfo.size / 2f)
                                                            ) {
                                                                pendingMoveTargetIdx = activeIdx + 1
                                                                latestOnMoveItem(activeIdx, activeIdx + 1)
                                                                dragOffsetY -= nextInfo.size
                                                            } else if (prevInfo != null &&
                                                                center < prevInfo.offset + (prevInfo.size / 2f)
                                                            ) {
                                                                pendingMoveTargetIdx = activeIdx - 1
                                                                latestOnMoveItem(activeIdx, activeIdx - 1)
                                                                dragOffsetY += prevInfo.size
                                                            }
                                                        }
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(LumynIcons.DragHandle),
                                            contentDescription = "Drag to reorder ${track.title}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    LumynArtwork(
                                        artworkUri = track.artworkUri,
                                        contentDescription = null,
                                        cornerRadius = 8.dp,
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            },
                            supportingContent = {
                                Text(
                                    text = "${track.artist} • ${formatDurationMs(track.durationMs)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CompactQueueActionButton(
                                        iconRes = LumynIcons.KeyboardArrowUp,
                                        contentDescription = "Move ${track.title} up",
                                        enabled = index > 0,
                                        onClick = { onMoveItem(index, index - 1) },
                                    )
                                    CompactQueueActionButton(
                                        iconRes = LumynIcons.KeyboardArrowDown,
                                        contentDescription = "Move ${track.title} down",
                                        enabled = index < queueEntries.lastIndex,
                                        onClick = { onMoveItem(index, index + 1) },
                                    )
                                    CompactQueueActionButton(
                                        iconRes = LumynIcons.Close,
                                        contentDescription = "Remove ${track.title} from queue",
                                        enabled = true,
                                        onClick = { onRemoveItem(index) },
                                    )
                                }
                            },
                            content = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            painter = painterResource(LumynIcons.GraphicEq),
                                            contentDescription = "Now playing",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(
                                        text = track.title,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class QueueSlot(
    val key: String,
    val track: Track,
)

@Composable
private fun CompactQueueActionButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SaveQueuePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    sleepTimer: SleepTimerState,
    onSetCountdown: (Long) -> Unit,
    onSetEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }

    if (showCustomDialog) {
        val pickerState = rememberTimePickerState(initialHour = 0, initialMinute = 30, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Sleep timer duration (hours : min)") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val totalMs = ((pickerState.hour * 60L) + pickerState.minute) * 60_000L
                        if (totalMs > 0L) onSetCountdown(totalMs)
                        showCustomDialog = false
                        onDismiss()
                    },
                    enabled = pickerState.hour > 0 || pickerState.minute > 0,
                ) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") }
            },
        ) {
            TimeInput(state = pickerState)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
                    val subtitle = when (sleepTimer) {
                        SleepTimerState.Off -> "Off"
                        SleepTimerState.EndOfTrack -> "Stops at end of current track"
                        is SleepTimerState.Countdown ->
                            "${QueueOperations.formatSleepTimerRemaining(sleepTimer.remainingMs)} remaining"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sleepTimer != SleepTimerState.Off) {
                    TextButton(
                        onClick = {
                            onCancel()
                            onDismiss()
                        },
                    ) { Text("Turn off") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
            com.lumyn.core.designsystem.component.LumynSegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val totalRows = 6
                SegmentedListItem(
                    onClick = {
                        onSetEndOfTrack()
                        onDismiss()
                    },
                    shapes = ListItemDefaults.segmentedShapes(0, totalRows),
                    colors = com.lumyn.core.designsystem.component.lumynSegmentedListItemColors(),
                    trailingContent = if (sleepTimer is SleepTimerState.EndOfTrack) {
                        { Icon(painter = painterResource(LumynIcons.Check), contentDescription = "Active") }
                    } else {
                        null
                    },
                    content = { Text("End of this track") },
                )
                val presetsMinutes = listOf(15, 30, 45, 60)
                presetsMinutes.forEachIndexed { i, minutes ->
                    SegmentedListItem(
                        onClick = {
                            onSetCountdown(minutes * 60_000L)
                            onDismiss()
                        },
                        shapes = ListItemDefaults.segmentedShapes(i + 1, totalRows),
                        colors = com.lumyn.core.designsystem.component.lumynSegmentedListItemColors(),
                        content = { Text("$minutes minutes") },
                    )
                }
                SegmentedListItem(
                    onClick = { showCustomDialog = true },
                    shapes = ListItemDefaults.segmentedShapes(5, totalRows),
                    colors = com.lumyn.core.designsystem.component.lumynSegmentedListItemColors(),
                    supportingContent = { Text("Choose hours & minutes via time dialog") },
                    content = { Text("Custom duration…") },
                )
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val sliderState = rememberSliderState(
        value = currentSpeed.coerceIn(0.5f, 2.0f),
        trackRange = 0.5f..2.0f,
    )
    LaunchedEffect(currentSpeed) {
        sliderState.value = currentSpeed.coerceIn(0.5f, 2.0f)
    }
    val displaySpeed = (sliderState.value * 20f).roundToInt() / 20f
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Playback speed", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    text = formatSpeedLabel(displaySpeed),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val selected = kotlin.math.abs(displaySpeed - preset) < 0.02f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            sliderState.value = preset
                            onSelectSpeed(preset)
                        },
                        label = { Text(formatSpeedLabel(preset)) },
                    )
                }
            }

            Slider(
                state = sliderState,
                onValueChange = { raw ->
                    sliderState.value = (raw * 20f).roundToInt() / 20f
                },
                onValueChangeFinished = {
                    onSelectSpeed((sliderState.value * 20f).roundToInt() / 20f)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        sliderState.value = 1.0f
                        onSelectSpeed(1.0f)
                    },
                ) { Text("Reset 1.0x") }
            }
        }
    }
}

internal fun formatSpeedLabel(speed: Float): String {
    val rounded = (speed * 100f).roundToInt() / 100f
    return if (rounded % 1f == 0f) {
        "${rounded.toInt()}.0x"
    } else if ((rounded * 10f) % 1f == 0f) {
        "%.1fx".format(rounded)
    } else {
        "%.2fx".format(rounded)
    }
}
