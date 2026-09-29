package org.unetd.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF1B4B43)
private val TealLight = Color(0xFF5CD6BE)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005145),
    secondary = Color(0xFFB0CCC4),
    background = Color(0xFF0E1513),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF1B2523),
    onSurfaceVariant = Color(0xFFBFC9C6),
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF4A635C),
    background = Color(0xFFF5FAF8),
    surface = Color(0xFFF5FAF8),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4945),
)

/** Green when a peer is up, amber while it is still settling, muted when idle. */
object StatusColors {
    val up = Color(0xFF3FBF8F)
    val pending = Color(0xFFE0A33E)
    val down = Color(0xFF8A9A96)
}

@Composable
fun UnetdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
