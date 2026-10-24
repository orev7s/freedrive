package app.freedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The whole Telegram setup, in the app, in the order it has to happen.
 *
 * Written against what actually fails: people get a token and then wonder why the check
 * says the chat is invisible, because a bot cannot see a chat until Start is pressed.
 */
@Composable
fun TelegramTutorialContent(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = "Telegram has no drive API, so this borrows the bot API: every image " +
                    "becomes a document in a chat you control. Two values to collect, about two minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(Steps.size) { index ->
            val step = Steps[index]
            Step(index + 1, step.first, step.second)
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("What this can and cannot do", style = MaterialTheme.typography.titleSmall)
                    Limits.forEach { (head, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(head, style = MaterialTheme.typography.labelLarge)
                            Text(
                                body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to the form")
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Command text is monospaced so it is obviously something to type, not read. */
private val Steps: List<Pair<String, String>> = listOf(
    "Create the bot" to
        "In Telegram, search for @BotFather (blue tick). Send /newbot, then answer its two " +
        "questions: a display name, and a username that has to end in bot.",
    "Copy the token" to
        "BotFather replies with a line like 123456789:AAE9kz… — that entire string is the token. " +
        "It is the bot's password: anyone who has it can post to and read the chat.",
    "Press Start" to
        "BotFather gives you a link to your new bot (t.me/yourbotname). Open it and tap Start. " +
        "A bot cannot message a person who has never started it — skip this and the check fails " +
        "with “chat not found”.",
    "Get your chat id" to
        "Message @userinfobot anything. It answers with Id: 81234567. That number is your chat id — " +
        "digits only, no @, no spaces.",
    "Paste both here" to
        "Go back one screen, paste the token and the number, then press Add and check. A working " +
        "account reads “@yourbot → your name”.",
    "Upload once to prove it" to
        "Send an image from the Files tab. It arrives in that chat as a document, and the app keeps " +
        "the message id so it can still open and delete it later.",
)

private val Limits: List<Pair<String, String>> = listOf(
    "20 MB per file" to
        "The bot API only lets a bot download 20 MB, so uploads stop at 20 MB too. Bigger images are " +
        "refused outright rather than stored where they could never be opened.",
    "Chat storage, not a drive" to
        "There are no folders on Telegram's side and no quota to report. Clearing the chat or deleting " +
        "it destroys the files, and this app cannot recover them.",
    "One bot per account" to
        "Add the provider again with a different token to get Telegram 2, Telegram 3 and so on. " +
        "Each bot is its own storage entry in the pool.",
    "Leaked token" to
        "Send /revoke to BotFather, paste the new token into a fresh entry, and remove the old one here.",
)
