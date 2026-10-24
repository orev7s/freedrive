package app.freedrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.freedrive.ui.FreeDriveRoot
import app.freedrive.ui.theme.FreeDriveTheme
import app.freedrive.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repo = (application as FreeDriveApp).repo
        val theme = ThemeStore(this)

        setContent {
            val mode by theme.mode.collectAsStateWithLifecycle()
            val dynamic by theme.dynamic.collectAsStateWithLifecycle()

            FreeDriveTheme(mode, dynamic) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val view = LocalView.current

                // Edge to edge: the app paints behind both bars, so only the icon tint flips.
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }

                FreeDriveRoot(repo, theme)
            }
        }
    }
}
