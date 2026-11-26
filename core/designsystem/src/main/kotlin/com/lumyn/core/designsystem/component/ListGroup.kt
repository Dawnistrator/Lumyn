package com.lumyn.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Section title above a [LumynListGroup]. */
@Composable
fun LumynSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Rounded `surfaceContainer` group for settings and info rows. Rows sit directly inside, no nested cards. */
@Composable
fun LumynListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(content = content)
    }
}

@Composable
fun LumynListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** List item colours that let the group's own container show through. */
@Composable
fun lumynGroupedListItemColors(): ListItemColors =
    ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A feature/capability row. The state is always written out as text, never colour alone.
 */
@Composable
fun LumynStatusRow(
    title: String,
    supporting: String,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        colors = lumynGroupedListItemColors(),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
