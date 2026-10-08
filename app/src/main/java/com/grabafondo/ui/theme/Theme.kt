package com.grabafondo.ui.theme

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

val RecordRed = Color(0xFFE53935)
val StopGray = Color(0xFF3C3C40)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    secondary = Color(0xFF775652),
    tertiary = Color(0xFF715B2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    secondary = Color(0xFFE7BDB7),
    tertiary = Color(0xFFE0C38C),
)

@Composable
fun GrabaFondoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
