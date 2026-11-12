package com.example.smartledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF2E7D6F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EDE6),
    onPrimaryContainer = Color(0xFF0B2B25),
    secondary = Color(0xFFB4593F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE3D9),
    onSecondaryContainer = Color(0xFF3D1508),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF1B2321),
    surface = Color.White,
    onSurface = Color(0xFF1B2321),
    surfaceVariant = Color(0xFFEDF2F0),
    onSurfaceVariant = Color(0xFF5C6C67),
    outline = Color(0xFFDCE4E1),
    error = Color(0xFFB3261E)
)

@Composable
fun SmartLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerColors,
        typography = Typography(),
        content = content
    )
}
