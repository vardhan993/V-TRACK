package com.vtrack.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography keeps Material 3 defaults but raises the smallest styles so nothing
 * drops below the 12sp accessibility floor, and gives numerals a tabular feel by
 * using tabular figures for metric styles.
 */
private val Default = Typography()

val VTrackTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.Bold, fontSize = 52.sp),
    displayMedium = Default.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Default.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Default.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Default.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Default.labelMedium.copy(fontSize = 12.sp),
    labelSmall = Default.labelSmall.copy(fontSize = 12.sp),
)

/** Metric numerals: big, tight, bold. */
val MetricNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = (-1).sp,
)

val MetricUnitStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 18.sp,
)
