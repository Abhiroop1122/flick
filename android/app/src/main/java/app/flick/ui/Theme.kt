package app.flick.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Brand {
    val Blue = Color(0xFF2F6BFF)
    val Cyan = Color(0xFF12C2E9)
    val Ok = Color(0xFF16A34A)
    val Bad = Color(0xFFE5484D)
    val gradient = Brush.linearGradient(
        colors = listOf(Blue, Cyan),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f),
    )
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF),
    onPrimaryContainer = Color(0xFF0A2A80),
    secondary = Brand.Cyan,
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5B6474),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFD5D9E0),
    outlineVariant = Color(0xFFE6E8ED),
    error = Brand.Bad,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6E95FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E2E5C),
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Brand.Cyan,
    background = Color(0xFF111316),
    onBackground = Color(0xFFF3F5F9),
    surface = Color(0xFF1B1E23),
    onSurface = Color(0xFFF3F5F9),
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFA7AFBD),
    surfaceContainerLow = Color(0xFF16181C),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF20242A),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF2C3037),
    error = Color(0xFFFF6B6F),
)

@Composable
fun FlickTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
