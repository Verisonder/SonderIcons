package com.verisonder.sondericons.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's colours, by role rather than by hue: [Black] is the background, [White] the
 * text and primary buttons, [Circle] raised surfaces, [Line] dividers. Each theme fills
 * the roles; red always means "needs you". OLED takes the phone's own theme colours.
 */
object Palette {
    var Black by mutableStateOf(Color(0xFF000000))
        private set
    var Circle by mutableStateOf(Color(0xFF1C1C1C))
        private set
    var Line by mutableStateOf(Color(0xFF2A2A2A))
        private set
    var White by mutableStateOf(Color(0xFFFFFFFF))
        private set
    var Muted by mutableStateOf(Color(0xFF8A8A8A))
        private set
    val Red = Color(0xFFD71921)
    var light by mutableStateOf(false)
        private set

    val THEMES = listOf("oled" to "OLED", "dark" to "Dark", "light" to "Light")

    fun apply(theme: String) {
        when (theme) {
            "dark" -> set(Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF2E2E2E), Color(0xFFF2F2F2), Color(0xFF9A9A9A), false)
            "light" -> set(Color(0xFFF7F7F7), Color(0xFFE9E9E9), Color(0xFFD5D5D5), Color(0xFF141414), Color(0xFF6E6E6E), true)
            else -> set(Color(0xFF000000), Color(0xFF1C1C1C), Color(0xFF2A2A2A), Color(0xFFFFFFFF), Color(0xFF8A8A8A), false)
        }
    }

    private fun set(bg: Color, surface: Color, line: Color, fg: Color, muted: Color, isLight: Boolean) {
        Black = bg; Circle = surface; Line = line; White = fg; Muted = muted; light = isLight
    }
}

// Three sizes, one family (the system's). Weight does the rest.
private val type = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
)

@Composable
fun SonderTheme(content: @Composable () -> Unit) {
    val p = Palette
    val scheme = if (p.light) lightColorScheme(
        primary = p.White, onPrimary = p.Black, secondary = p.Muted, onSecondary = p.Black,
        background = p.Black, onBackground = p.White, surface = p.Black, onSurface = p.White,
        surfaceVariant = p.Circle, onSurfaceVariant = p.Muted,
        surfaceContainerLow = p.Circle, surfaceContainer = p.Circle, surfaceContainerHigh = p.Circle,
        outline = p.Line, outlineVariant = p.Line, error = p.Red,
    ) else darkColorScheme(
        primary = p.White, onPrimary = p.Black, secondary = p.Muted, onSecondary = p.Black,
        background = p.Black, onBackground = p.White, surface = p.Black, onSurface = p.White,
        surfaceVariant = p.Circle, onSurfaceVariant = p.Muted,
        surfaceContainerLow = p.Circle, surfaceContainer = p.Circle, surfaceContainerHigh = p.Circle,
        outline = p.Line, outlineVariant = p.Line, error = p.Red,
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
