package dev.ryunosuke.island.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ryunosuke.island.ui.island.IslandColors

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = IslandColors.Orange,
            onPrimary = Color.Black,
            secondary = IslandColors.Green,
            background = Color.Black,
            surface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFF2C2C2E),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFAEAEB2),
        ),
        content = content,
    )
}
