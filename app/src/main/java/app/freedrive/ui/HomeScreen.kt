package app.freedrive.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedrive.data.db.EntryEntity
import coil.compose.AsyncImage
import java.io.File

/**
 * The folder you are in. Folders are rows, images are a plain three-up grid with no
 * captions — the thumbnail is the label. Long-press deletes, tap opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesContent(state: AppState, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TargetBar(state)
        if (state.items.isEmpty()) {
            EmptyFolder(state, onPick)
        } else {
            FileGrid(state)
        }
    }
}

/**
 * One line, above the grid: where the next upload goes. "Any available" is the default and
 * survives nothing — a choice is only kept across restarts when the box is ticked.
 */
@Composable
private fun TargetBar(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val chosen = state.providers.firstOrNull { it.id == state.targetProviderId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(
                onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = chosen?.label ?: "Any available",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Any available")
                            Text(
                                "Goes wherever there is most room",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        state.setTarget(null)
                    },
                )
                if (state.providers.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                state.providers.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.label)
                                if (provider.status != "OK") {
                                    Text(
                                        "not ready — check it first",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        onClick = {
                            open = false
                            state.setTarget(provider.id)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Checkbox(
            checked = state.rememberTarget,
            onCheckedChange = state::setRemember,
        )
        Text(
            text = "Remember",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun EmptyFolder(state: AppState, onPick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.verifiedCount == 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No provider yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add one to get somewhere to put files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { state.tab(Tab.PROVIDERS) }) { Text("Add a provider") }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Empty folder", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Images you add are spread across your providers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onPick) { Text("Upload images") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(state: AppState) {
    val folders = state.items.filter { it.isFolder }
    val files = state.items.filterNot { it.isFolder }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Folders") }
            items(folders, key = { it.id }) { folder ->
                FolderRow(
                    entry = folder,
                    onClick = { state.open(folder) },
                    onLongClick = { state.confirmDelete = folder },
                )
            }
        }
        if (files.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Images") }
            items(files, key = { it.id }) { file ->
                ImageTile(
                    entry = file,
                    onClick = { state.open(file) },
                    onLongClick = { state.confirmDelete = file },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(entry: EntryEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageTile(entry: EntryEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    AsyncImage(
        model = entry.thumbPath?.let(::File),
        contentDescription = entry.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

