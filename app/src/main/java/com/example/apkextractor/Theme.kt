package com.example.apkextractor

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme options exposed in the picker.
 *
 * SYSTEM follows the device's light/dark setting automatically (via
 * isSystemInDarkTheme), so the app flips with the OS. AMOLED is a dark theme
 * with a pure-black background, which saves power and looks sharp on OLED
 * screens.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED black");

    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Pure-black dark scheme for OLED displays. */
private val AmoledColorScheme: ColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1C1C1C),
)

/** Resolve the active Material color scheme for [mode], honoring the OS for SYSTEM. */
@Composable
fun colorSchemeFor(mode: ThemeMode): ColorScheme {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    return when {
        mode == ThemeMode.AMOLED -> AmoledColorScheme
        useDark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

/** Tiny SharedPreferences-backed store so the choice survives restarts. */
object ThemeStore {
    private const val PREFS = "settings"
    private const val KEY = "theme_mode"

    fun load(context: Context): ThemeMode =
        ThemeMode.from(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
