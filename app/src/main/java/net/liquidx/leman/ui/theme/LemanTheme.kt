package net.liquidx.leman.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * Custom design system; Material3 is a substrate only — theming, a11y
 * semantics — with no Material components in the visual tree (spec 06).
 */
@Composable
fun LemanTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = LemanColors.accent,
        background = LemanColors.base,
        surface = LemanColors.surface,
        onPrimary = LemanColors.base,
        onBackground = LemanColors.textPrimary,
        onSurface = LemanColors.textPrimary,
        error = LemanColors.danger,
    )
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalIndication provides LemanIndication,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = LemanColors.accent,
                backgroundColor = LemanColors.accentTint,
            ),
        ) {
            androidx.compose.material3.ProvideTextStyle(LemanType.body, content)
        }
    }
}

/** App background helper — everything sits on `base`. */
fun Modifier.lemanScreenBackground(): Modifier = background(LemanColors.base)
