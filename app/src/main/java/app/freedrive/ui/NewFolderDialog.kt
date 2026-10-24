package app.freedrive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Material 3's own dialog and text field. The field takes focus on open, the IME's
 * action key creates the folder, and the typed text stays visible above the keyboard —
 * so there is nothing to close before pressing Create.
 */
@Composable
fun NewFolderDialog(state: AppState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || working) return
        working = true
        scope.launch {
            val failure = state.createFolder(trimmed)
            working = false
            if (failure == null) onDismiss() else error = failure
        }
    }

    // The dialog window is attached a frame later than the composition.
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("New folder") },
        text = {
            // If the dialog window is not resized for the IME, this lifts the field above it.
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = name.isNotBlank() && !working) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
        },
    )
}
