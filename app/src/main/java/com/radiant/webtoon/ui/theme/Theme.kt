package com.radiant.webtoon.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AmoledColorScheme = darkColorScheme(
    primary = FilamentAmber,
    onPrimary = FilamentOnAmber,
    primaryContainer = Color(0xFF3A2A08),
    onPrimaryContainer = FilamentAmber,
    secondary = FilamentTeal,
    onSecondary = Color(0xFF04241C),
    tertiary = FilamentEmber,
    onTertiary = Color(0xFF2A0C06),
    background = Color.Black,
    onBackground = FilamentPaper,
    surface = Color.Black,
    onSurface = FilamentPaper,
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = FilamentMuted,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    outline = Color(0xFF3A3832),
)

private val DarkColorScheme = darkColorScheme(
    primary = FilamentAmber,
    onPrimary = FilamentOnAmber,
    primaryContainer = Color(0xFF3A2A08),
    onPrimaryContainer = FilamentAmber,
    secondary = FilamentTeal,
    onSecondary = Color(0xFF04241C),
    tertiary = FilamentEmber,
    onTertiary = Color(0xFF2A0C06),
    background = FilamentInk,
    onBackground = FilamentPaper,
    surface = FilamentPanel,
    onSurface = FilamentPaper,
    surfaceVariant = FilamentRaised,
    onSurfaceVariant = FilamentMuted,
    surfaceContainerLowest = FilamentInk,
    surfaceContainerLow = Color(0xFF101218),
    surfaceContainer = FilamentPanel,
    surfaceContainerHigh = FilamentRaised,
    surfaceContainerHighest = Color(0xFF262A34),
    outline = Color(0xFF3A3832),
)

private val LightColorScheme = lightColorScheme(
    primary = FilamentAmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A8),
    onPrimaryContainer = FilamentOnAmber,
    secondary = Color(0xFF0E6B56),
    onSecondary = Color.White,
    tertiary = Color(0xFFC2410C),
    onTertiary = Color.White,
    background = GlassLightBackground,
    onBackground = FilamentInk,
    surface = GlassLightSurface,
    onSurface = FilamentInk,
    surfaceVariant = GlassLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5C564C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3EEE4),
    surfaceContainer = Color(0xFFEDE7DB),
    surfaceContainerHigh = Color(0xFFE4DDD0),
    surfaceContainerHighest = Color(0xFFD9D1C3),
    outline = Color(0xFFC8C0B2),
)

@Composable
fun WebtoonTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark", "amoled" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val useAmoled = themeMode == "amoled"

    val colorScheme = when {
        useAmoled -> AmoledColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = androidx.compose.ui.graphics.Color.Transparent.toArgb()
            window.navigationBarColor = androidx.compose.ui.graphics.Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
