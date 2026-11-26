package com.lumyn.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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

/** Section title above a [LumynListGroup] or [LumynSegmentedColumn]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LumynSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Rounded `surfaceContainer` group for read-only info rows. */
@Composable
fun LumynListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.largeIncreased,
    ) {
        Column(content = content)
    }
}

/** Expressive segmented list container with [ListItemDefaults.SegmentedGap] between interactive items. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LumynSegmentedColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
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

/** Elevated surface container colours for Material 3 Expressive segmented list items. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun lumynSegmentedListItemColors(): ListItemColors =
    ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

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
        supportingContent = { Text(supporting) },
        trailingContent = {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = { Text(title) },
    )
}
