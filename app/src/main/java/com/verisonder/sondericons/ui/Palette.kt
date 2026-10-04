package com.verisonder.sondericons.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Taken from the phone, not invented: the theme draws its icons on #1C1C1C circles over
 * black, and uses one red for things that are on. Here red means "needs you", nothing else.
 */
object Palette {
    val Black = Color(0xFF000000)
    val Circle = Color(0xFF1C1C1C)     // the theme's own icon background
    val Line = Color(0xFF2A2A2A)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8A8A8A)
    val Red = Color(0xFFD71921)
}

private val scheme = darkColorScheme(
    primary = Palette.White, onPrimary = Palette.Black,
    secondary = Palette.Muted, onSecondary = Palette.Black,
    background = Palette.Black, onBackground = Palette.White,
    surface = Palette.Black, onSurface = Palette.White,
    surfaceVariant = Palette.Circle, onSurfaceVariant = Palette.Muted,
    surfaceContainerLow = Palette.Circle, surfaceContainer = Palette.Circle, surfaceContainerHigh = Palette.Circle,
    outline = Palette.Line, outlineVariant = Palette.Line,
    error = Palette.Red,
)

// Three sizes, one family (the system's). Weight does the rest.
private val type = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
)

@Composable
fun SonderTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
