package com.ygmusic.stream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = YG_Gold,
    secondary = YG_Gray,
    background = YG_Black,
    surface = YG_Black
)

@Composable
fun YGMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}