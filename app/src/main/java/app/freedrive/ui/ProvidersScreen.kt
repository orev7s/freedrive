package app.freedrive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import app.freedrive.data.AdapterKind
import app.freedrive.data.Catalog
import app.freedrive.data.ProviderSpec
import app.freedrive.data.db.ProviderEntity

@Composable
fun ProvidersContent(state: AppState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { StorageCard(state) }

        item { SectionLabel("Connected") }

        if (state.providers.isEmpty()) {
            item {
                Text(
                    "Nothing connected yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }

        items(state.providers, key = { it.id }) { provider ->
            ProviderRow(state, provider)
        }

        item {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = state::openCatalog, modifier = Modifier.fillMaxWidth()) {
                Text("Add provider")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StorageCard(state: AppState) {
    val fraction = if (state.quota > 0) (state.used.toDouble() / state.quota).toFloat().coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "${fmtBytes(state.freeBytes)} free",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${fmtBytes(state.used)} used · ${state.verifiedCount} of ${state.providers.size} ready",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun ProviderRow(state: AppState, provider: ProviderEntity) {
    var menu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(provider.label) },
        supportingContent = {
            Text(
                text = when (provider.status) {
                    // Telegram has no quota to be a fraction of; the 10 GB is our own guess.
                    "OK" -> if (state.specOf(provider)?.appReservedQuota == true) {
                        "${fmtBytes(provider.usedBytes)} stored · no provider quota"
                    } else {
                        "${fmtBytes(provider.usedBytes)} of ${fmtBytes(provider.quotaBytes)} used"
                    }
                    "CHECKING" -> "Checking…"
                    "FAILED" -> provider.lastError ?: "Check failed"
                    else -> "Not checked yet"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = { StatusDot(provider.status == "OK") },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Options for ${provider.label}",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Check again") },
                        onClick = { menu = false; state.recheck(provider.id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = { menu = false; state.removeProvider(provider.id) },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun CatalogContent(state: AppState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Text(
                text = "Keys only. No account is opened for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        items(Catalog.all, key = { it.id }) { spec ->
            val count = state.countOf(spec)
            val short = spec.tagline.substringAfter("· ").replaceFirstChar { it.uppercase() }

            ListItem(
                headlineContent = { Text(spec.name) },
                supportingContent = {
                    Text(
                        text = if (count > 0) "$count connected · $short" else short,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { state.openAdd(spec) },
            )
        }
    }
}

@Composable
fun AddProviderContent(state: AppState, spec: ProviderSpec) {
    var values by remember(spec.id) {
        mutableStateOf(spec.fields.associate { it.key to it.prefill })
    }
    var reveal by remember(spec.id) { mutableStateOf(false) }

    val complete = spec.fields.all { it.optional || values[it.key]?.isNotBlank() == true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = spec.steps,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (spec.kind == AdapterKind.TELEGRAM) {
            FilledTonalButton(
                onClick = { state.openTutorial(spec) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Step-by-step setup")
            }
        }

        spec.caution?.let { caution ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = caution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        spec.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.key].orEmpty(),
                onValueChange = { values = values + (field.key to it) },
                label = { Text(field.label) },
                placeholder = if (field.hint.isNotEmpty()) {
                    { Text(field.hint) }
                } else null,
                singleLine = true,
                enabled = state.busy == null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                visualTransformation = if (field.secret && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (field.secret) {
                    {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (reveal) "Hide" else "Show",
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { state.addProvider(spec, values) },
            enabled = complete && state.busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy != null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Add and check")
            }
        }
    }
}
