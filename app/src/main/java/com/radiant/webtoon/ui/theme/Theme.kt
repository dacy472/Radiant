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
    primary = GlassPrimary,
    secondary = GlassSecondary,
    tertiary = GlassTertiary,
    background = Color.Black,
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF141414),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = GlassDarkOnBackground,
    onSurface = GlassDarkOnBackground,
    onSurfaceVariant = GlassDarkOnBackground,
)

private val DarkColorScheme = darkColorScheme(
    primary = GlassPrimary,
    secondary = GlassSecondary,
    tertiary = GlassTertiary,
    background = GlassDarkBackground,
    surface = GlassDarkSurface,
    surfaceVariant = GlassDarkSurfaceVariant,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    onBackground = GlassDarkOnBackground,
    onSurface = GlassDarkOnBackground,
    onSurfaceVariant = GlassDarkOnBackground
)

private val LightColorScheme = lightColorScheme(
    primary = GlassPrimary,
    secondary = GlassSecondary,
    tertiary = GlassTertiary,
    background = GlassLightBackground,
    surface = GlassLightSurface,
    surfaceVariant = GlassLightSurfaceVariant,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    onBackground = GlassLightOnBackground,
    onSurface = GlassLightOnBackground,
    onSurfaceVariant = GlassLightOnBackground
)

@Composable
fun WebtoonTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
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
