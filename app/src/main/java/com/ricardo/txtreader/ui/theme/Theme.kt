package com.ricardo.txtreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Blue80,
    secondary = Slate80,
    background = InkDark,
    surface = InkDark
)

private val LightColors = lightColorScheme(
    primary = Blue40,
    secondary = Slate40,
    background = Color(0xFFF6F8FB),
    surface = Color.White
)

@Composable
fun TxtReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
