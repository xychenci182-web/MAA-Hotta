package com.aliothmoon.maahotta.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val MahCyan = Color(0xFF007E9B)
val MahGold = Color(0xFF9A6500)
val MahGreen = Color(0xFF087B65)
val MahRed = Color(0xFFB3261E)
val MahBackground = Color(0xFFFFFFFF)
val MahSurface = Color(0xFFF8FAFD)
val MahSurfaceHigh = Color(0xFFECF2F8)
val MahOutline = Color(0xFFB7C7D8)
val MahTextMuted = Color(0xFF5D7085)

private val colors = lightColorScheme(
    primary = MahCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F3FA),
    onPrimaryContainer = Color(0xFF003640),
    secondary = MahGold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8B7),
    onSecondaryContainer = Color(0xFF392A00),
    tertiary = MahGreen,
    onTertiary = Color.White,
    background = MahBackground,
    onBackground = Color(0xFF162536),
    surface = MahSurface,
    onSurface = Color(0xFF162536),
    surfaceVariant = MahSurfaceHigh,
    onSurfaceVariant = Color(0xFF42566C),
    outline = MahOutline,
    outlineVariant = Color(0xFFD5E0EB),
    error = MahRed,
    onError = Color.White,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MaaHottaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
