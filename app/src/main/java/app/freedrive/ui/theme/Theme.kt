package app.freedrive.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One accent colour, two schemes, three user choices.
 *
 * Material 3 supplies every component; this file only supplies the palette, so the app
 * stops looking like a hand-rolled imitation and starts behaving like a real Android app.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persisted with SharedPreferences — no DataStore dependency for one enum. */
class ThemeStore(context: Context) {

    private val prefs = context.getSharedPreferences("freedrive.ui", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM),
    )
    val mode: StateFlow<ThemeMode> = _mode

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private companion object { const val KEY = "theme_mode" }
}

val LocalThemeStore = staticCompositionLocalOf<ThemeStore> { error("ThemeStore not provided") }

private val Accent = Color(0xFF2C5CFF)
private val AccentLight = Color(0xFF7C9BFF)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF5A6072),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E7F0),
    onSecondaryContainer = Color(0xFF171B24),
    tertiary = Color(0xFF4B607C),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0B0D10),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0D10),
    surfaceVariant = Color(0xFFE9EBF0),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFECEEF2),
    surfaceContainerHighest = Color(0xFFE6E9EF),
    outline = Color(0xFF9AA3B0),
    outlineVariant = Color(0xFFE2E5EB),
    error = Color(0xFFC62B1F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E5),
    onErrorContainer = Color(0xFF5C0F08),
)

private val Dark = darkColorScheme(
    primary = AccentLight,
    onPrimary = Color(0xFF00174A),
    primaryContainer = Color(0xFF1B3A8F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB6BECE),
    onSecondary = Color(0xFF141820),
    secondaryContainer = Color(0xFF262B36),
    onSecondaryContainer = Color(0xFFE3E7EF),
    tertiary = Color(0xFFA8C4E8),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE8EAEE),
    surface = Color(0xFF0C0E12),
    onSurface = Color(0xFFE8EAEE),
    surfaceVariant = Color(0xFF2A2F39),
    onSurfaceVariant = Color(0xFFA5AEBC),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF13161B),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF1D222A),
    surfaceContainerHighest = Color(0xFF242A33),
    outline = Color(0xFF6E7785),
    outlineVariant = Color(0xFF2C323C),
    error = Color(0xFFFF8A7D),
    onError = Color(0xFF4A0B05),
    errorContainer = Color(0xFF6B1509),
    onErrorContainer = Color(0xFFFFDAD3),
)

@Composable
fun FreeDriveTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}
