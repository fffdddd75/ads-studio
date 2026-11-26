@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.adsstudio

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра «монтажной»: тёплый safelight-оранжевый, холодный циан монитора,
 * чернильный фон и кремовый текст. Никаких дефолтных фиолетовых градиентов.
 */
object StudioColors {
    val Ink = Color(0xFF07080C)
    val InkSoft = Color(0xFF0B0D13)
    val Surface = Color(0xFF11141C)
    val SurfaceRaised = Color(0xFF171B25)
    val SurfaceHigh = Color(0xFF1E2330)
    val Border = Color(0xFF2A3040)
    val BorderSoft = Color(0xFF202533)
    val Text = Color(0xFFF6F1E7)
    val TextMuted = Color(0xFFA9ADBA)
    val TextDim = Color(0xFF7C8294)
    val Orange = Color(0xFFFF6B3D)
    val Coral = Color(0xFFFF4D6D)
    val Cyan = Color(0xFF6EE7F9)
    val Violet = Color(0xFF9B8CFF)
    val Lime = Color(0xFFC7F464)
    val Green = Color(0xFF7BE0A3)
    val Red = Color(0xFFFF5C5C)
}

private val Unbounded = FontFamily(
    Font(
        R.font.unbounded,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.unbounded,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.unbounded,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.unbounded,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        R.font.unbounded,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900))
    )
)

private val Manrope = FontFamily(
    Font(
        R.font.manrope,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))
    )
)

object StudioType {
    val wordmark = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.Black,
        fontSize = 19.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp
    )
    val hero = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.Bold,
        fontSize = 37.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp
    )
    val section = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp
    )
    val monoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.7.sp
    )
    val label = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.1.sp
    )
}

private val StudioTypography = Typography(
    displayLarge = StudioType.hero,
    displayMedium = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.9).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = StudioType.section,
    titleLarge = TextStyle(
        fontFamily = Unbounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.7.sp
    )
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val StudioScheme = darkColorScheme(
    primary = StudioColors.Orange,
    onPrimary = Color.White,
    primaryContainer = StudioColors.Orange.copy(alpha = 0.18f),
    onPrimaryContainer = StudioColors.Text,
    secondary = StudioColors.Cyan,
    onSecondary = StudioColors.Ink,
    secondaryContainer = StudioColors.Cyan.copy(alpha = 0.14f),
    onSecondaryContainer = StudioColors.Text,
    tertiary = StudioColors.Violet,
    onTertiary = StudioColors.Ink,
    background = StudioColors.Ink,
    onBackground = StudioColors.Text,
    surface = StudioColors.Surface,
    onSurface = StudioColors.Text,
    surfaceVariant = StudioColors.SurfaceRaised,
    onSurfaceVariant = StudioColors.TextMuted,
    outline = StudioColors.Border,
    outlineVariant = StudioColors.BorderSoft,
    error = StudioColors.Red,
    onError = Color.White,
    errorContainer = StudioColors.Red.copy(alpha = 0.16f),
    onErrorContainer = StudioColors.Text
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content
    )
}
