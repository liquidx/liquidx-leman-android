package net.liquidx.leman.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.defaultMinSize
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanMotion
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.glow
import net.liquidx.leman.ui.theme.hairline

enum class DotStyle { Running, NeedsYou, Hollow, Failed }

/** 9dp status dot (spec 06): running pulses; hollow = 1px faint ring. */
@Composable
fun StatusDot(style: DotStyle, modifier: Modifier = Modifier, description: String = "") {
    Box(
        modifier = modifier
            .size(9.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            DotStyle.Running -> {
                val transition = rememberInfiniteTransition(label = "pulse")
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(LemanMotion.pulseDurationMillis),
                        RepeatMode.Restart,
                    ),
                    label = "halo",
                )
                // pulsing halo: scale .5→2.6, alpha .8→0
                Box(
                    Modifier
                        .size(9.dp)
                        .scale(0.5f + progress * 2.1f)
                        .alpha((1f - progress) * 0.8f)
                        .clip(CircleShape)
                        .background(LemanColors.accent),
                )
                Box(
                    Modifier
                        .size(9.dp)
                        .glow(LemanColors.accent, 6.dp, 0.6f)
                        .clip(CircleShape)
                        .background(LemanColors.accent),
                )
            }
            DotStyle.NeedsYou -> Box(
                Modifier.size(9.dp).clip(CircleShape).background(LemanColors.warn),
            )
            DotStyle.Failed -> Box(
                Modifier.size(9.dp).clip(CircleShape).background(LemanColors.danger),
            )
            DotStyle.Hollow -> Box(
                Modifier
                    .size(9.dp)
                    .drawBehind {
                        drawCircle(
                            color = LemanColors.textFaint,
                            style = Stroke(width = 1f),
                            radius = size.minDimension / 2f - 0.5f,
                        )
                    },
            )
        }
    }
}

/**
 * Thread row (spec 06/2a): dot · title/preview/meta · pin. Row tap opens; the
 * pin glyph has its own ≥44dp hit area.
 */
@Composable
fun ThreadRow(
    title: String,
    preview: String,
    stateLabel: String,
    stateColor: Color,
    timeLabel: String,
    dot: DotStyle,
    unread: Boolean,
    pinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    sourceLabel: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .hairline(HairlineSide.Bottom, LemanColors.hairlineFaint)
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, top = 13.dp, bottom = 13.dp),
    ) {
        StatusDot(dot, description = stateLabel)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = LemanType.value,
                    color = if (unread) LemanColors.textPrimary else LemanColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    Box(
                        Modifier
                            .padding(start = 7.dp)
                            .size(5.dp)
                            .background(LemanColors.accent),
                    )
                }
            }
            Text(
                preview,
                style = LemanType.meta.copy(fontSize = 11.sp),
                color = LemanColors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 3.dp)) {
                if (sourceLabel != null) {
                    Text("$sourceLabel · ", style = LemanType.meta, color = LemanColors.textFaint)
                }
                Text("▪ ", style = LemanType.meta, color = stateColor)
                Text(stateLabel, style = LemanType.meta, color = stateColor)
                Text(" · $timeLabel", style = LemanType.meta, color = LemanColors.textFaint)
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onTogglePin)
                .semantics { contentDescription = if (pinned) "unpin" else "pin" },
        ) {
            Text(
                if (pinned) "◆" else "◇",
                style = LemanType.value,
                color = if (pinned) LemanColors.accent else LemanColors.pinIdle,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A, widthDp = 380)
@Composable
private fun ThreadRowPreview() {
    Column {
        ThreadRow(
            title = "book train to geneva",
            preview = "found 3 options under 80 chf — need your pick",
            stateLabel = "needs you",
            stateColor = LemanColors.warn,
            timeLabel = "09:12",
            dot = DotStyle.NeedsYou,
            unread = true,
            pinned = true,
            onOpen = {},
            onTogglePin = {},
        )
        ThreadRow(
            title = "renew car insurance",
            preview = "comparing quotes from 4 providers",
            stateLabel = "running · 3/5",
            stateColor = LemanColors.accent,
            timeLabel = "now",
            dot = DotStyle.Running,
            unread = false,
            pinned = true,
            onOpen = {},
            onTogglePin = {},
        )
        ThreadRow(
            title = "fix flaky ci pipeline",
            preview = "patched retry backoff; 40 green runs",
            stateLabel = "done",
            stateColor = LemanColors.textFaint,
            timeLabel = "21:44",
            dot = DotStyle.Hollow,
            unread = false,
            pinned = false,
            onOpen = {},
            onTogglePin = {},
        )
    }
}
