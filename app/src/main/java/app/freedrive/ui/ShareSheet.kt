package app.freedrive.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freedrive.data.Lifetime
import app.freedrive.data.ShareKind
import app.freedrive.data.ShareLink
import app.freedrive.data.db.EntryEntity
import kotlinx.coroutines.delay

/**
 * One sheet, because a file is shared once: pick how long it should live, get a link, send it.
 *
 * The timer and the link are the same decision, not two. Whatever is chosen here is stamped on
 * the file *and* used as the link's window, so there is no state where a working URL points at
 * something that has already been deleted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(state: AppState, entry: EntryEntity, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Text("Send", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${entry.name}  ·  ${fmtBytes(entry.size)}  ·  ${entry.providerName ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            SectionLabel("Delete after")

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lifetime.entries.forEach { option ->
                    FilterChip(
                        selected = state.shareLifetime == option,
                        onClick = { state.chooseShareLifetime(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Text(
                text = timerCopy(entry, state.shareLifetime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "Deadlines are checked every few seconds, and by a system alarm while the app is closed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(20.dp))

            val link = state.shareLink
            if (link == null) {
                Button(
                    onClick = state::createLink,
                    enabled = !state.shareBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.shareBusy) "Working…" else "Create link")
                }
            } else {
                LinkBlock(
                    link = link,
                    copied = copied,
                    onCopy = {
                        clipboard.setText(AnnotatedString(link.url))
                        copied = true
                    },
                    onSend = {
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, entry.name)
                            .putExtra(Intent.EXTRA_TEXT, link.url)
                        context.startActivity(Intent.createChooser(intent, "Send link"))
                    },
                )
            }

            state.shareError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 4.dp))

            WifiBlock(state)
        }
    }
}

@Composable
private fun LinkBlock(
    link: ShareLink,
    copied: Boolean,
    onCopy: () -> Unit,
    onSend: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = link.url,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (link.kind == ShareKind.NATIVE) "pCloud link" else "Signed link") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onCopy) {
                if (copied) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copied")
                } else {
                    Text("Copy link")
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSend) { Text("Send…") }
            Spacer(Modifier.weight(1f))
            link.diesAt?.let {
                Text(
                    text = fmtRemaining(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = link.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The optional half. Off unless the switch in Settings was turned on, and always described with
 * its two conditions: same network, app open.
 */
@Composable
private fun WifiBlock(state: AppState) {
    val lan = state.lanLink

    when {
        !state.wifiSending -> {
            SectionLabel("Peer to peer")
            Text(
                text = "Send the file straight from this phone to another one on the same Wi-Fi. " +
                    "No upload, no link, nothing stored in between — but both phones have to be on " +
                    "that network and this screen has to stay open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { state.enableWifiSending(true) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Turn on Wi-Fi sending")
            }
        }

        lan == null -> {
            SectionLabel("Peer to peer")
            Text(
                text = "The other phone joins this Wi-Fi, opens the address, and saves the file. " +
                    "It stops working when this sheet closes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = state::sendOverWifi,
                enabled = !state.shareBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (state.shareBusy) "Preparing…" else "Send over Wi-Fi")
            }
        }

        else -> {
            SectionLabel("Peer to peer · live")
            OutlinedTextField(
                value = lan.url,
                onValueChange = {},
                readOnly = true,
                label = { Text("Open this on the other phone") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (lan.served == 0) "Not downloaded yet" else "Downloaded ${lan.served}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "closes ${fmtIn(lan.diesAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = state::stopWifi) { Text("Stop") }
            }
        }
    }
}

private fun timerCopy(entry: EntryEntity, option: Lifetime): String {
    val where = entry.providerName ?: "its provider"
    return when (option) {
        Lifetime.KEEP -> "The image stays on $where. A link still expires after 7 days — " +
            "that is the longest any provider will sign."
        else -> "The image is deleted from $where in ${option.label}, and the link goes with it."
    }
}
