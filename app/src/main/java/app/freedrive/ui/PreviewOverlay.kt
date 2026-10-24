package app.freedrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedrive.data.adapters.readable
import app.freedrive.data.db.EntryEntity
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File

/**
 * The image itself, edge to edge, with the facts in a single strip at the bottom.
 * Black here regardless of theme: a photo viewer should not tint the photo.
 */
@Composable
fun PreviewOverlay(state: AppState, entry: EntryEntity, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    var file by remember(entry.id) { mutableStateOf<File?>(null) }
    var failure by remember(entry.id) { mutableStateOf<String?>(null) }
    var now by remember(entry.id) { mutableStateOf(System.currentTimeMillis()) }
    var menu by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(entry.id, entry.expiresAt) {
        while (entry.expiresAt != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(entry.id) {
        runCatching { state.repo.originalFile(entry.id) }
            .onSuccess { file = it }
            .onFailure { failure = it.readable() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {

            when {
                file != null -> AsyncImage(
                    model = file,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                failure != null -> Text(
                    text = failure!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9BEC7),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { state.openShare(entry) }) {
                        Icon(
                            Icons.Outlined.IosShare,
                            contentDescription = "Send",
                            tint = Color.White,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save to device") },
                                onClick = {
                                    menu = false
                                    state.saveToDevice(entry)
                                },
                            )
                            state.providers
                                .filter { it.id != entry.providerId && it.status == "OK" }
                                .forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text("Move to ${target.label}") },
                                        onClick = {
                                            menu = false
                                            state.moveTo(entry, target)
                                        },
                                    )
                                }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menu = false
                                    state.confirmDelete = entry
                                },
                            )
                        }
                    }
                }
                Text(
                    text = facts(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7CCD4),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
                entry.expiresAt?.let { at ->
                    Text(
                        text = fmtRemaining(at, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF2C48D),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Spacer(Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** provider · size · pixels · date — the four things worth knowing about a stored image. */
private fun facts(entry: EntryEntity): String {
    val parts = buildList {
        entry.providerName?.let(::add)
        add(fmtBytes(entry.size))
        if (entry.width != null && entry.height != null) add("${entry.width}×${entry.height}")
        add(fmtDate(entry.createdAt))
    }
    return parts.joinToString("  ·  ")
}
