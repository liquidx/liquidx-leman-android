package net.liquidx.leman.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanMotion
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.glow
import net.liquidx.leman.ui.theme.hairlineBorder

/** The blinking caret block — 1s steps(1) on/off (spec 06). */
@Composable
fun BlinkingCaret(modifier: Modifier = Modifier, width: Int = 7, height: Int = 14) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = LemanMotion.caretPeriodMillis
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    Box(
        modifier
            .size(width.dp, height.dp)
            .alpha(alpha)
            .background(LemanColors.accent),
    )
}

/**
 * The one reusable input (spec 06): accent `>` prompt, mono text, blinking
 * caret when empty, right `⏎ hint`. Hairline variant for search/composer;
 * accent-border variant for the primary new-thread affordances.
 */
@Composable
fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    accentBorder: Boolean = false,
    enabled: Boolean = true,
    onSubmit: () -> Unit = {},
) {
    val border = if (accentBorder) LemanColors.accent else LemanColors.hairlineStrong
    val alpha = if (enabled) 1f else 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(LemanColors.surface)
            .hairlineBorder(border.copy(alpha = border.alpha * alpha))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("> ", style = LemanType.body, color = LemanColors.accent.copy(alpha = alpha))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        placeholder,
                        style = LemanType.body,
                        color = LemanColors.textFaint.copy(alpha = alpha),
                    )
                    if (enabled) BlinkingCaret(Modifier.padding(start = 6.dp))
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = LemanType.body.copy(color = LemanColors.textPrimary),
                cursorBrush = SolidColor(LemanColors.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (value.isNotBlank()) onSubmit() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        hint?.let {
            Text(
                it,
                style = LemanType.micro,
                color = LemanColors.textFaint.copy(alpha = alpha),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 40×20 square toggle (spec 06): on = accent border + tint + glowing accent
 * knob right; off = .18 hairline + faint knob left. Knob slides 120ms linear.
 */
@Composable
fun LemanToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(LemanMotion.toggleKnobMillis, easing = LinearEasing),
        label = "knob",
    )
    Box(
        modifier = modifier
            .size(40.dp, 20.dp)
            .background(if (checked) LemanColors.accentTint else LemanColors.surface)
            .hairlineBorder(if (checked) LemanColors.accent else LemanColors.hairlineStrong)
            .clickable { onCheckedChange(!checked) }
            .semantics { stateDescription = if (checked) "on" else "off" },
    ) {
        Box(
            Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(16.dp)
                .then(if (checked) Modifier.glow(LemanColors.accent, 8.dp, 0.6f) else Modifier)
                .background(if (checked) LemanColors.accent else LemanColors.textFaint),
        )
    }
}

/** 38×38 avatar glyph tile, single-select (2d, spec 06). */
@Composable
fun GlyphTile(
    glyph: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(38.dp)
            .background(if (selected) androidx.compose.ui.graphics.Color(0x1F6BA0D8) else androidx.compose.ui.graphics.Color.Transparent)
            .hairlineBorder(if (selected) LemanColors.accent else androidx.compose.ui.graphics.Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .semantics { stateDescription = if (selected) "selected" else "unselected" },
    ) {
        Text(
            glyph,
            style = LemanType.value,
            color = if (selected) LemanColors.accent else LemanColors.textFaint,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A, widthDp = 380)
@Composable
private fun InputsPreview() {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        PromptField("", {}, placeholder = "filter threads", hint = "⏎ find")
        PromptField("", {}, placeholder = "new thread", hint = "⏎ start", accentBorder = true)
        PromptField("book train to geneva", {}, placeholder = "message juno", hint = "⏎ send")
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            LemanToggle(true, {})
            LemanToggle(false, {})
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            listOf("✳", "◆", "▲", "●", "⌬").forEachIndexed { i, g -> GlyphTile(g, i == 0, {}) }
        }
    }
}
