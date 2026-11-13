package com.motogps.android.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Moto Chat GPS — Dark Touring. Shared semantic colors for the Android UI. */
object MotoChatColors {
    val Background = Color(0xFF080C0B)
    val Surface = Color(0xFF151D1B)
    val SurfaceRaised = Color(0xFF1E2925)
    val Green = Color(0xFF50D995)
    val GreenSoft = Color(0xFF24352D)
    val OnGreenSoft = Color(0xFF78E4AC)
    val Text = Color(0xFFF3F7F5)
    val TextMuted = Color(0xFFA3B2AC)
    val Outline = Color(0xFF45534E)
    val Error = Color(0xFFFFB4AB)
}

val MotoChatDarkColorScheme = darkColorScheme(
    primary = MotoChatColors.Green,
    onPrimary = MotoChatColors.Background,
    primaryContainer = MotoChatColors.GreenSoft,
    onPrimaryContainer = MotoChatColors.OnGreenSoft,
    secondary = MotoChatColors.TextMuted,
    onSecondary = MotoChatColors.Background,
    secondaryContainer = MotoChatColors.SurfaceRaised,
    onSecondaryContainer = MotoChatColors.Text,
    tertiary = MotoChatColors.OnGreenSoft,
    onTertiary = MotoChatColors.Background,
    background = MotoChatColors.Background,
    onBackground = MotoChatColors.Text,
    surface = MotoChatColors.Surface,
    onSurface = MotoChatColors.Text,
    surfaceVariant = MotoChatColors.SurfaceRaised,
    onSurfaceVariant = MotoChatColors.TextMuted,
    outline = MotoChatColors.Outline,
    outlineVariant = MotoChatColors.Outline,
    error = MotoChatColors.Error,
    onError = MotoChatColors.Background,
    inverseSurface = MotoChatColors.Text,
    inverseOnSurface = MotoChatColors.Background,
    inversePrimary = MotoChatColors.GreenSoft,
    surfaceTint = MotoChatColors.Green
)
