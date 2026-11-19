package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.data.ThemeMode

val LocalVoxKeyPalette = staticCompositionLocalOf { LightVoxKeyPalette }

object VoxKeyThemeTokens {
  val palette: VoxKeyPalette
    @Composable @ReadOnlyComposable get() = LocalVoxKeyPalette.current
}

private val LightM3Scheme =
  lightColorScheme(
    primary = LightVoxKeyPalette.dictateAccent,
    onPrimary = LightVoxKeyPalette.onDictateAccent,
    secondary = LightVoxKeyPalette.navigateAccent,
    onSecondary = LightVoxKeyPalette.onNavigateAccent,
    background = LightVoxKeyPalette.keyboardBackground,
    onBackground = LightVoxKeyPalette.inkPrimary,
    surface = LightVoxKeyPalette.keySurface,
    onSurface = LightVoxKeyPalette.inkPrimary,
    surfaceVariant = LightVoxKeyPalette.traySurface,
    onSurfaceVariant = LightVoxKeyPalette.inkSecondary,
    outline = LightVoxKeyPalette.keyBorder,
    error = LightVoxKeyPalette.errorAccent,
  )

private val DarkM3Scheme =
  darkColorScheme(
    primary = DarkVoxKeyPalette.dictateAccent,
    onPrimary = DarkVoxKeyPalette.onDictateAccent,
    secondary = DarkVoxKeyPalette.navigateAccent,
    onSecondary = DarkVoxKeyPalette.onNavigateAccent,
    background = DarkVoxKeyPalette.keyboardBackground,
    onBackground = DarkVoxKeyPalette.inkPrimary,
    surface = DarkVoxKeyPalette.keySurface,
    onSurface = DarkVoxKeyPalette.inkPrimary,
    surfaceVariant = DarkVoxKeyPalette.traySurface,
    onSurfaceVariant = DarkVoxKeyPalette.inkSecondary,
    outline = DarkVoxKeyPalette.keyBorder,
    error = DarkVoxKeyPalette.errorAccent,
  )

@Composable
fun VoxKeyTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val darkTheme =
    when (themeMode) {
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }
  val palette = if (darkTheme) DarkVoxKeyPalette else LightVoxKeyPalette
  val colorScheme = if (darkTheme) DarkM3Scheme else LightM3Scheme

  CompositionLocalProvider(LocalVoxKeyPalette provides palette) {
    MaterialTheme(colorScheme = colorScheme, typography = VoxKeyTypography, content = content)
  }
}
