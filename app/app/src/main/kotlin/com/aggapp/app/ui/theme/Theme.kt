package com.aggapp.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Brand.Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E2FF),
    onPrimaryContainer = Color(0xFF000E66),
    secondary = Brand.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBDDFF),
    onSecondaryContainer = Color(0xFF2B0053),
    tertiary = Brand.Amber,
    onTertiary = Color(0xFF3D2400),
    tertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF6F5FB),
    onBackground = Color(0xFF1A1B23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFFE4E1F0),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF1A23A8),
    primaryContainer = Color(0xFF353FCC),
    onPrimaryContainer = Color(0xFFE2E2FF),
    secondary = Color(0xFFD3BAFF),
    onSecondary = Color(0xFF44187A),
    secondaryContainer = Color(0xFF5D3A9E),
    onSecondaryContainer = Color(0xFFEBDDFF),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF4A2600),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF12131A),
    onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF1A1B23),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF2A2B35),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF928F9A),
)

/** 重构后圆角体系：大卡 24dp，中卡 16dp，胶囊 full */
val CardShapeLarge = RoundedCornerShape(24.dp)
val CardShapeMedium = RoundedCornerShape(16.dp)

@Composable
fun AggTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = MaterialTheme.shapes.copy(
            large = CardShapeLarge,
            medium = CardShapeMedium,
        ),
        content = content,
    )
}
