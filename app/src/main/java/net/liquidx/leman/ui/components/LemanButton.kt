package net.liquidx.leman.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairlineBorder

enum class LemanButtonKind { Primary, Neutral, Danger, Dismiss }

/**
 * Square mono buttons (spec 06): primary = accent text/border/tint fill,
 * neutral = `#C9C9D0` + .18 hairline, danger = red text + red hairline,
 * dismissive = faint. No radius, no elevation.
 */
@Composable
fun LemanButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: LemanButtonKind = LemanButtonKind.Neutral,
    enabled: Boolean = true,
) {
    val (text, border, fill) = when (kind) {
        LemanButtonKind.Primary -> Triple(LemanColors.accent, LemanColors.accent, LemanColors.accentTint)
        LemanButtonKind.Neutral -> Triple(Color(0xFFC9C9D0), Color(0x2EFFFFFF), Color.Transparent)
        LemanButtonKind.Danger -> Triple(LemanColors.danger, LemanColors.dangerBorder, Color.Transparent)
        LemanButtonKind.Dismiss -> Triple(LemanColors.textFaint, Color(0x1FFFFFFF), Color.Transparent)
    }
    val alpha = if (enabled) 1f else 0.45f
    Text(
        text = label,
        style = LemanType.body.copy(fontSize = 11.5.sp),
        color = text.copy(alpha = text.alpha * alpha),
        maxLines = 1,
        softWrap = false,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(fill)
            .hairlineBorder(border.copy(alpha = border.alpha * alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A)
@Composable
private fun LemanButtonPreview() {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(12.dp),
    ) {
        LemanButton("confirm booking", {}, kind = LemanButtonKind.Primary)
        LemanButton("see options", {})
        LemanButton("clear all threads", {}, kind = LemanButtonKind.Danger)
        LemanButton("dismiss", {}, kind = LemanButtonKind.Dismiss)
    }
}
