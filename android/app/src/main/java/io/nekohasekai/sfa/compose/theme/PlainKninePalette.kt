package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal fun plainKnineColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFB3CDDB), onPrimary = Color(0xFF163747),
    primaryContainer = Color(0xFF294755), onPrimaryContainer = Color(0xFFDAEDF5),
    secondary = Color(0xFFB8C7CE), onSecondary = Color(0xFF24343D),
    secondaryContainer = Color(0xFF344650), onSecondaryContainer = Color(0xFFDAE6EC),
    tertiary = Color(0xFFB3CDDB), onTertiary = Color(0xFF163747),
    tertiaryContainer = Color(0xFF294755), onTertiaryContainer = Color(0xFFDAEDF5),
    background = Color(0xFF101A20), onBackground = Color(0xFFE0E8EC),
    surface = Color(0xFF162129), onSurface = Color(0xFFE0E8EC),
    surfaceContainer = Color(0xFF1B2932), surfaceContainerLow = Color(0xFF17232B),
    surfaceContainerHigh = Color(0xFF24333D), surfaceContainerHighest = Color(0xFF2B3B45),
    onSurfaceVariant = Color(0xFFB7C3CB), outline = Color(0xFF84939C), outlineVariant = Color(0xFF374A55),
    surfaceTint = Color.Transparent,
) else lightColorScheme(
    primary = Color(0xFF294F64), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EDF3), onPrimaryContainer = Color(0xFF244252),
    secondary = Color(0xFF526671), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EEF2), onSecondaryContainer = Color(0xFF304550),
    tertiary = Color(0xFF294F64), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1EDF3), onTertiaryContainer = Color(0xFF244252),
    background = Color(0xFFF7F9FA), onBackground = Color(0xFF1B2932),
    surface = Color.White, onSurface = Color(0xFF1B2932),
    surfaceContainer = Color(0xFFF0F4F6), surfaceContainerLow = Color(0xFFF6F8F9),
    surfaceContainerHigh = Color(0xFFEDF1F4), surfaceContainerHighest = Color(0xFFE7EDF1),
    onSurfaceVariant = Color(0xFF586C78), outline = Color(0xFF899AA4), outlineVariant = Color(0xFFD8E2E8),
    surfaceTint = Color.Transparent,
)
