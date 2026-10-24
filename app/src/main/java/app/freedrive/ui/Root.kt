package app.freedrive.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedrive.data.FreeDriveRepo
import app.freedrive.ui.theme.ThemeStore

/**
 * One Scaffold, three tabs, two overlay screens. Everything visual comes from Material 3:
 * the top bar, the bottom bar, the floating action button, the dialogs and the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeDriveRoot(repo: FreeDriveRepo, theme: ThemeStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = remember(repo) {
        AppState(repo, theme, UploadTargetStore(context), SharePrefs(context), scope)
    }
    val snackbar = remember { SnackbarHostState() }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        state.upload(uris)
    }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        state.error = null
        snackbar.showSnackbar(message)
    }

    val deep = state.route != Route.Root
    val inFolder = state.tab == Tab.FILES && state.folderId != null
    BackHandler(enabled = deep || inFolder) {
        if (deep) state.back() else state.goUp()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { AppBar(state) },
            bottomBar = { if (state.route == Route.Root) Tabs(state) },
            floatingActionButton = {
                if (state.route == Route.Root && state.tab == Tab.FILES) {
                    FloatingActionButton(onClick = { pickImages.launch("image/*") }) {
                        Icon(Icons.Filled.Add, contentDescription = "Upload images")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                if (state.pendingCount > 0) QueueStrip(state)
                when (val route = state.route) {
                    Route.Root -> when (state.tab) {
                        Tab.FILES -> FilesContent(state, onPick = { pickImages.launch("image/*") })
                        Tab.PROVIDERS -> ProvidersContent(state)
                        Tab.SETTINGS -> SettingsContent(state)
                    }

                    Route.Catalog -> CatalogContent(state)
                    is Route.AddProvider -> AddProviderContent(state, route.spec)
                    is Route.Tutorial -> TelegramTutorialContent(onBack = state::back)
                }
            }
        }

        val preview = state.preview
        if (state.previewId != null && preview != null) {
            PreviewOverlay(state, preview, onClose = state::closePreview)
        }
    }

    if (state.newFolderOpen) {
        NewFolderDialog(state, onDismiss = { state.newFolderOpen = false })
    }
    if (state.queueOpen) {
        QueueSheet(state, onDismiss = { state.queueOpen = false })
    }
    state.shareFor?.let { entry ->
        ShareSheet(state, entry, onDismiss = state::closeShare)
    }
    state.confirmDelete?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onCancel = { state.confirmDelete = null },
            onConfirm = { state.delete(entry) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(state: AppState) {
    val deep = state.route != Route.Root
    val canGoUp = deep || (state.tab == Tab.FILES && state.folderId != null)

    TopAppBar(
        title = {
            Text(
                text = when (val route = state.route) {
                    Route.Root -> when (state.tab) {
                        Tab.FILES -> state.folderTitle
                        Tab.PROVIDERS -> "Providers"
                        Tab.SETTINGS -> "Settings"
                    }

                    Route.Catalog -> "Add provider"
                    is Route.AddProvider -> route.spec.name
                    is Route.Tutorial -> "Telegram setup"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (canGoUp) {
                IconButton(onClick = { if (deep) state.back() else state.goUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (state.route == Route.Root && state.tab == Tab.FILES) {
                IconButton(onClick = state::openSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                IconButton(onClick = { state.newFolderOpen = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
                if (state.uploads.isNotEmpty()) {
                    BadgedBox(
                        badge = {
                            if (state.pendingCount > 0) Badge { Text(state.pendingCount.toString()) }
                        },
                    ) {
                        IconButton(onClick = { state.queueOpen = true }) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = "Upload queue")
                        }
                    }
                }
            }
            if (state.route == Route.Root && state.tab == Tab.PROVIDERS) {
                IconButton(onClick = state::openCatalog) {
                    Icon(Icons.Filled.Add, contentDescription = "Add provider")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
private fun Tabs(state: AppState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = state.tab == Tab.FILES,
            onClick = { state.tab(Tab.FILES) },
            icon = {
                Icon(
                    if (state.tab == Tab.FILES) Icons.Filled.Folder else Icons.Outlined.Folder,
                    contentDescription = null,
                )
            },
            label = { Text("Files") },
        )
        NavigationBarItem(
            selected = state.tab == Tab.PROVIDERS,
            onClick = { state.tab(Tab.PROVIDERS) },
            icon = {
                Icon(
                    if (state.tab == Tab.PROVIDERS) Icons.Filled.Cloud else Icons.Outlined.Cloud,
                    contentDescription = null,
                )
            },
            label = { Text("Providers") },
        )
        NavigationBarItem(
            selected = state.tab == Tab.SETTINGS,
            onClick = { state.tab(Tab.SETTINGS) },
            icon = {
                Icon(
                    if (state.tab == Tab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = null,
                )
            },
            label = { Text("Settings") },
        )
    }
}

/** A three-pixel strip under the bar: how much of the whole queue is done, at a glance. */
@Composable
private fun QueueStrip(state: AppState) {
    val count = state.pendingCount
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { state.queueOpen = true }
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (count == 1) "Uploading 1 file" else "Uploading $count files",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(state.queueFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.queueFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape),
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    entry: app.freedrive.data.db.EntryEntity,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (entry.isFolder) "Delete folder?" else "Delete image?") },
        text = {
            Text(
                if (entry.isFolder) "\"${entry.name}\" and everything inside it will be removed from its provider."
                else "\"${entry.name}\" will be removed from ${entry.providerName ?: "its provider"}.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun StatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun EmptyHint(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
