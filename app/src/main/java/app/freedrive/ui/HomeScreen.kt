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
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color

/**
 * The folder you are in. Folders are rows, images are a plain three-up grid with no
 * captions — the thumbnail is the label. Long-press deletes, tap opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesContent(state: AppState, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.searchOpen) SearchField(state) else {
            TargetBar(state)
            ViewBar(state)
        }
        if (state.selection.isNotEmpty()) SelectionBar(state)

        when {
            state.searchOpen -> SearchResults(state)
            state.items.isEmpty() -> EmptyFolder(state, onPick)
            state.listMode -> FileList(state)
            else -> FileGrid(state)
        }
    }
}

/** Sort order is view state only — the index is untouched, so switching is instant. */
private fun arrange(list: List<EntryEntity>, sort: SortBy): List<EntryEntity> = when (sort) {
    SortBy.NEWEST -> list.sortedWith(compareByDescending<EntryEntity> { it.isFolder }.thenByDescending { it.createdAt })
    SortBy.NAME -> list.sortedWith(
        compareByDescending<EntryEntity> { it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )
    SortBy.LARGEST -> list.sortedWith(compareByDescending<EntryEntity> { it.isFolder }.thenByDescending { it.size })
}

@Composable
private fun SearchField(state: AppState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    OutlinedTextField(
        value = state.query,
        onValueChange = state::updateQuery,
        placeholder = { Text("Search images") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            IconButton(onClick = state::closeSearch) {
                Icon(Icons.Filled.Close, contentDescription = "Close search", modifier = Modifier.size(18.dp))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun ViewBar(state: AppState) {
    var sortOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { sortOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(state.sort.label, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortBy.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            sortOpen = false
                            state.applySort(option)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = state::toggleListMode) {
            Icon(
                if (state.listMode) Icons.Filled.GridView else Icons.Filled.ViewList,
                contentDescription = if (state.listMode) "Grid view" else "List view",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SelectionBar(state: AppState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.selection.size} selected",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = state::clearSelection) { Text("Cancel") }
        TextButton(onClick = state::deleteSelected) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SearchResults(state: AppState) {
    if (state.query.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Type a name. Search covers every folder and every provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    if (state.searchResults.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (state.searching) "Searching…" else "Nothing matches \"${state.query}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.searchResults, key = { it.id }) { hit ->
            ListItem(
                headlineContent = { Text(hit.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "${hit.providerName ?: "—"} · ${fmtBytes(hit.size)} · ${fmtDate(hit.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { StatusDot(ok = true) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    state.closeSearch()
                    state.open(hit)
                },
            )
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
    val arranged = arrange(state.items, state.sort)
    val folders = arranged.filter { it.isFolder }
    val files = arranged.filterNot { it.isFolder }
    val selecting = state.selection.isNotEmpty()

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
                    selected = folder.id in state.selection,
                    onClick = {
                        if (selecting) state.toggleSelection(folder.id) else state.open(folder)
                    },
                    onLongClick = { state.toggleSelection(folder.id) },
                )
            }
        }
        if (files.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Images") }
            items(files, key = { it.id }) { file ->
                ImageTile(
                    entry = file,
                    selected = file.id in state.selection,
                    onClick = {
                        if (selecting) state.toggleSelection(file.id) else state.open(file)
                    },
                    onLongClick = { state.toggleSelection(file.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(state: AppState) {
    val arranged = arrange(state.items, state.sort)
    val selecting = state.selection.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        arranged.forEach { entry ->
            item(key = entry.id) {
                ListItem(
                    headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            if (entry.isFolder) "Folder" else
                                "${entry.providerName ?: "—"} · ${fmtBytes(entry.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (entry.isFolder) Icons.Outlined.Folder else Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        if (entry.id in state.selection) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.combinedClickable(
                        onClick = { if (selecting) state.toggleSelection(entry.id) else state.open(entry) },
                        onLongClick = { state.toggleSelection(entry.id) },
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    entry: EntryEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
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
private fun ImageTile(
    entry: EntryEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = entry.thumbPath?.let(::File),
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

