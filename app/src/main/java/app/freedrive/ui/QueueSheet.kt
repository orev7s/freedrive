package app.freedrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedrive.data.db.UploadEntity
import app.freedrive.data.db.UploadState

/**
 * The queue is database state, not UI state: closing this sheet, changing tabs or
 * killing the app does not stop a transfer. Every row reads its bytes from the socket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: AppState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val finished = state.uploads.any { it.state == UploadState.DONE || it.state == UploadState.FAILED }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Uploads", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (finished) {
                    TextButton(onClick = state::clearFinished) { Text("Clear finished") }
                }
            }

            if (state.uploads.isEmpty()) {
                EmptyHint("Nothing queued", "Images you upload show up here until you clear them.")
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(state.uploads, key = { it.id }) { upload -> UploadRow(state, upload) }
            }
        }
    }
}

@Composable
private fun UploadRow(state: AppState, upload: UploadEntity) {
    val movable = upload.state == UploadState.QUEUED || upload.state == UploadState.RUNNING
    val done = state.bytesOf(upload).coerceIn(0L, upload.size.coerceAtLeast(0L))
    val fraction = if (upload.size > 0L) (done.toDouble() / upload.size).toFloat() else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = upload.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!movable) {
                IconButton(onClick = { state.dismissUpload(upload.id) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove from list",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        when (upload.state) {
            UploadState.QUEUED -> Text(
                text = "Waiting · to ${state.labelFor(upload.targetProviderId)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UploadState.RUNNING -> {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp)
                        .heightIn(min = 4.dp)
                        .clip(CircleShape),
                )
                Text(
                    text = "${(fraction * 100).toInt()}%   ${fmtBytes(done)} of ${fmtBytes(upload.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UploadState.DONE -> Text(
                text = upload.providerName?.let { "Stored on $it" } ?: "Stored",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            else -> Column {
                Text(
                    text = upload.error ?: "Upload failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 16.dp),
                )
                TextButton(
                    onClick = { state.retry(upload.id) },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
