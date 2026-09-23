package uk.krodity.beam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Beam blue, on near-black. Dynamic colour is deliberately not used:
// a remote should look the same on every phone it is installed on.
private val Blue = Color(0xFF3B82F6)
private val BlueDim = Color(0xFF1D4ED8)

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueDim,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFBFD3F2),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB4B4B4),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = BlueDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B1B3F),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0EFEE),
    outline = Color(0xFFD4D4D4),
)

@Composable
fun BeamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
