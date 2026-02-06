package com.madhuram.ideacoach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Seaweed,
    secondary = Sunbeam,
    background = Sand,
    surface = Sand,
    onPrimary = Color.White,
    onSecondary = Ink,
    onBackground = Ink,
    onSurface = Ink
)

private val DarkColors = darkColorScheme(
    primary = Seaweed,
    secondary = Sunbeam,
    background = Ink,
    surface = Ink,
    onPrimary = Color.White,
    onSecondary = Ink,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun IdeaCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}
