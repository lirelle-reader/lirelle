package app.tfdn.lirelle.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

private val Default = Typography()

/** Serif for the display and headline roles and for `titleLarge`; the platform sans-serif for everything else. */
internal val LirelleTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = Default.displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = Default.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = Default.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = Default.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = Default.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = Default.titleLarge.copy(fontFamily = FontFamily.Serif),
)
