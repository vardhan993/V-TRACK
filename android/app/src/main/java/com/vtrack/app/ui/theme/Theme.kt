package com.vtrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = ElectricGreen,
    onPrimary = OnElectricGreen,
    primaryContainer = Color(0xFF0B5E33),
    onPrimaryContainer = ElectricGreenContainer,
    secondary = ChartTeal,
    onSecondary = Color(0xFF00201D),
    tertiary = CoinGold,
    onTertiary = Color(0xFF2A1D00),
    background = MidnightBlack,
    onBackground = TextPrimaryDark,
    surface = MidnightNavy,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceNavy,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = SurfaceNavy,
    surfaceContainerHigh = SurfaceNavyHigh,
    outline = OutlineNavy,
    outlineVariant = Color(0xFF1E2A40),
    error = DangerRed,
    onError = Color(0xFF2A0004),
)

private val LightScheme = lightColorScheme(
    primary = ElectricGreenDeep,
    onPrimary = Color.White,
    primaryContainer = ElectricGreenContainer,
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    tertiary = CoinGoldDeep,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = Color(0xFFEDF2F7),
    surfaceContainerHigh = Color(0xFFE2E9F0),
    outline = Color(0xFFB6C2CF),
    outlineVariant = Color(0xFFD8E0E8),
    error = Color(0xFFC0262B),
    onError = Color.White,
)

val VTrackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Brand-only tokens that Material 3 does not model. */
@Immutable
data class VTrackExtraColors(
    val coinGold: Color,
    val streakOrange: Color,
    val chartBlue: Color,
    val chartPurple: Color,
    val chartTeal: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val metricNumber: TextStyle,
    val metricUnit: TextStyle,
)

private val DarkExtra = VTrackExtraColors(
    coinGold = CoinGold,
    streakOrange = StreakOrange,
    chartBlue = ChartBlue,
    chartPurple = ChartPurple,
    chartTeal = ChartTeal,
    success = SuccessGreen,
    warning = WarningAmber,
    danger = DangerRed,
    metricNumber = MetricNumberStyle,
    metricUnit = MetricUnitStyle,
)

private val LightExtra = DarkExtra.copy(
    coinGold = CoinGoldDeep,
    streakOrange = Color(0xFFB5540F),
    chartBlue = Color(0xFF1565C0),
    chartPurple = Color(0xFF5B3FBF),
    chartTeal = Color(0xFF00796B),
    warning = Color(0xFF9A6400),
)

val LocalVTrackColors = staticCompositionLocalOf { DarkExtra }

/** Convenience accessor: `VTrackTheme.colors.coinGold`. */
object VTrackTheme {
    val colors: VTrackExtraColors
        @Composable get() = LocalVTrackColors.current
}

@Composable
fun VTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val extra = if (darkTheme) DarkExtra else LightExtra

    CompositionLocalProvider(LocalVTrackColors provides extra) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VTrackTypography,
            shapes = VTrackShapes,
            content = content,
        )
    }
}
