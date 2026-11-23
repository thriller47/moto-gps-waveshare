package com.motogps.android.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motogps.android.R

/**
 * Moto Chat GPS — Stitch "Dark Touring".
 *
 * Tokens are shared by the home cockpit, route preview, bottom sheets and dialogs.
 * Official Space Grotesk and Manrope variable Open Font License resources are
 * bundled in res/font so the app typography also works fully offline.
 */
object MotoChatColors {
    val Background = Color(0xFF080C0B)
    val Surface = Color(0xFF151D1B)
    val SurfaceRaised = Color(0xFF1E2925)
    val SurfaceHigh = Color(0xFF27342E)
    val SurfaceLowest = Color(0xFF0B0F0E)
    val Green = Color(0xFF50D995)
    val GreenPressed = Color(0xFF2BD47D)
    val GreenSoft = Color(0xFF24352D)
    val OnGreenSoft = Color(0xFF78E4AC)
    val OnGreen = Color(0xFF041E11)
    val Text = Color(0xFFF3F7F5)
    val TextMuted = Color(0xFFA3B2AC)
    val Outline = Color(0xFF273832)
    val Warning = Color(0xFFE5A93C)
    val Error = Color(0xFFFF5C5C)
    val Scrim = Color(0xFF040605)
}

val MotoChatDarkColorScheme = darkColorScheme(
    primary = MotoChatColors.Green,
    onPrimary = MotoChatColors.OnGreen,
    primaryContainer = MotoChatColors.GreenSoft,
    onPrimaryContainer = MotoChatColors.OnGreenSoft,
    secondary = MotoChatColors.TextMuted,
    onSecondary = MotoChatColors.OnGreen,
    secondaryContainer = MotoChatColors.SurfaceRaised,
    onSecondaryContainer = MotoChatColors.Text,
    tertiary = MotoChatColors.OnGreenSoft,
    onTertiary = MotoChatColors.OnGreen,
    background = MotoChatColors.Background,
    onBackground = MotoChatColors.Text,
    surface = MotoChatColors.Surface,
    onSurface = MotoChatColors.Text,
    surfaceVariant = MotoChatColors.SurfaceRaised,
    onSurfaceVariant = MotoChatColors.TextMuted,
    outline = MotoChatColors.Outline,
    outlineVariant = MotoChatColors.Outline,
    error = MotoChatColors.Error,
    onError = MotoChatColors.OnGreen,
    inverseSurface = MotoChatColors.Text,
    inverseOnSurface = MotoChatColors.Background,
    inversePrimary = MotoChatColors.GreenSoft,
    surfaceTint = MotoChatColors.Green,
    scrim = MotoChatColors.Scrim
)

private val TouringDisplay = FontFamily(
    Font(R.font.space_grotesk_variable, FontWeight.Normal),
    Font(R.font.space_grotesk_variable, FontWeight.Medium),
    Font(R.font.space_grotesk_variable, FontWeight.SemiBold),
    Font(R.font.space_grotesk_variable, FontWeight.Bold)
)
private val TouringBody = FontFamily(
    Font(R.font.manrope_variable, FontWeight.Normal),
    Font(R.font.manrope_variable, FontWeight.Medium),
    Font(R.font.manrope_variable, FontWeight.SemiBold),
    Font(R.font.manrope_variable, FontWeight.Bold)
)

val MotoChatTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = TouringDisplay, fontSize = 32.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em
    ),
    headlineLarge = TextStyle(
        fontFamily = TouringDisplay, fontSize = 24.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em
    ),
    headlineMedium = TextStyle(
        fontFamily = TouringDisplay, fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = TextStyle(
        fontFamily = TouringDisplay, fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontFamily = TouringDisplay, fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontFamily = TouringDisplay, fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontFamily = TouringDisplay, fontSize = 16.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontFamily = TouringBody, fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyMedium = TextStyle(
        fontFamily = TouringBody, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    ),
    bodySmall = TextStyle(
        fontFamily = TouringBody, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontFamily = TouringDisplay, fontSize = 16.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.02.em
    ),
    labelMedium = TextStyle(
        fontFamily = TouringDisplay, fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.03.em
    ),
    labelSmall = TextStyle(
        fontFamily = TouringDisplay, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.06.em
    )
)

val MotoChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
