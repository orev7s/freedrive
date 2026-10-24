package app.freedrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.freedrive.data.Lifetime
import app.freedrive.ui.theme.ThemeMode

/**
 * Theme choice plus the one number that matters. No cards, no explanatory paragraphs —
 * three radios and three rows of text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(state: AppState) {
    val mode by state.theme.mode.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SectionLabel("Theme")

        Column(Modifier.selectableGroup()) {
            ThemeMode.values().forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == option,
                            role = Role.RadioButton,
                            onClick = { state.setTheme(option) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))

        SectionLabel("Storage")
        StatRow("Free", fmtBytes(state.freeBytes))
        StatRow("Used", fmtBytes(state.used))
        StatRow("Providers", "${state.verifiedCount} of ${state.providers.size} ready")
        StatRow("Uploads waiting", state.pendingCount.toString())

        HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))

        // ---------------------------------------------------------------- sending

        SectionLabel("Sending")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Send over Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Off by default. When on, a shared file is served from this phone to the other one, directly, for 30 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = state.wifiSending, onCheckedChange = state::enableWifiSending)
        }

        Text(
            text = "Both phones must be on the same Wi-Fi and this app has to stay open while the other one downloads. " +
                "Nothing goes through a server, so it works when the provider has no share links — Telegram and WebDAV. " +
                "It does not work over mobile data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.width(4.dp))

        Text(
            text = "New uploads delete after",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lifetime.entries.forEach { option ->
                FilterChip(
                    selected = state.defaultLifetime == option,
                    onClick = { state.chooseDefaultLifetime(option) },
                    label = { Text(option.label) },
                )
            }
        }

        Text(
            text = "Starts counting when the file finishes uploading, not when you queue it. " +
                "Files already in your library keep their own timer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text(
            text = "Space adds up across providers; it is not copied between them. Each image lives on exactly one provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun label(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
