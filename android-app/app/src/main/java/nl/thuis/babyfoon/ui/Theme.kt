package nl.thuis.babyfoon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Donker thema: prettig 's nachts, weinig licht in de slaapkamer. */
private val Colors = darkColorScheme(
    primary = Color(0xFF8EC5FF),
    onPrimary = Color(0xFF0B1A2A),
    secondary = Color(0xFFF2D58A),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
    surfaceVariant = Color(0xFF1F2730),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

val Groen = Color(0xFF5BD68A)
val Oranje = Color(0xFFFFB454)
val Rood = Color(0xFFFF6B6B)

@Composable
fun BabyfoonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
