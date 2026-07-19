package net.liquidx.leman.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import net.liquidx.leman.ui.theme.hairlineOver

enum class DotStyle { Running, NeedsYou, Hollow, Failed }

/** Width of the delete action uncovered when a row is swiped fully open. */
private val DELETE_REVEAL_WIDTH = 88.dp

/**
 * Fling speed (px/s) that opens or closes a row regardless of how far it was
 * dragged, so a quick flick doesn't require crossing the halfway point.
 */
private const val SWIPE_FLING_VELOCITY = 400f

/**
 * No bounce: the row is tracking a finger, and overshoot reads as sloppiness
 * rather than personality. Interruptible by construction — a new drag retargets
 * the same Animatable mid-flight instead of queueing behind it.
 */
private val swipeSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

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
 *
 * Swiping left slides the row to uncover a delete button sitting beneath it.
 * The row follows the finger 1:1 and settles open or closed on release. While
 * open, tapping the row body closes it rather than opening the thread, so the
 * destructive button is never adjacent to a tap that does something else.
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
    deleteRevealed: Boolean = false,
    deleteFailed: Boolean = false,
    onRevealDelete: () -> Unit = {},
    onHideDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
) {
    val revealPx = with(LocalDensity.current) { DELETE_REVEAL_WIDTH.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Follows state driven from outside the row: another row opening, or the
    // thread being deleted out from under an open row.
    LaunchedEffect(deleteRevealed, revealPx) {
        offsetX.animateTo(if (deleteRevealed) -revealPx else 0f, swipeSpring)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The divider belongs to the row's slot, not the sliding surface.
            .hairlineOver(HairlineSide.Bottom, LemanColors.hairlineFaint)
            // A drag is unreachable with a screen reader, so expose delete as a
            // custom action too. It confirms directly: the swipe-then-tap
            // sequence exists to prevent slips, which isn't how this is invoked.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("delete thread") { onConfirmDelete(); true },
                )
            },
    ) {
        DeleteAction(
            onClick = onConfirmDelete,
            // Fades and scales in as the row uncovers it, so the button reads as
            // physically behind the row rather than popping in at the end.
            progress = (-offsetX.value / revealPx).coerceIn(0f, 1f),
            // matchParentSize, not fillMaxHeight: inside a LazyColumn the height
            // constraint is infinite, so fillMaxHeight silently does nothing and
            // would leave untappable strips above and below the button.
            modifier = Modifier.matchParentSize(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque: the action underneath must not show through the row.
                .background(LemanColors.base)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = { velocity ->
                        val open = velocity < -SWIPE_FLING_VELOCITY ||
                            (velocity <= SWIPE_FLING_VELOCITY && offsetX.value <= -revealPx / 2f)
                        // Settle locally first: if `open` matches the current
                        // state the LaunchedEffect won't re-fire, and the row
                        // would otherwise be stranded mid-drag.
                        launch { offsetX.animateTo(if (open) -revealPx else 0f, swipeSpring) }
                        if (open != deleteRevealed) {
                            if (open) onRevealDelete() else onHideDelete()
                        }
                    },
                )
                .clickable(onClick = if (deleteRevealed) onHideDelete else onOpen)
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
                    if (deleteFailed) {
                        Text(
                            "delete failed · swipe to retry",
                            style = LemanType.meta,
                            color = LemanColors.danger,
                        )
                    } else {
                        if (sourceLabel != null) {
                            Text("$sourceLabel · ", style = LemanType.meta, color = LemanColors.textFaint)
                        }
                        Text("▪ ", style = LemanType.meta, color = stateColor)
                        Text(stateLabel, style = LemanType.meta, color = stateColor)
                        Text(" · $timeLabel", style = LemanType.meta, color = LemanColors.textFaint)
                    }
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
}

/**
 * The destructive action uncovered behind a swiped row. Fades and scales with
 * the reveal so it reads as sitting underneath rather than appearing beside.
 * Always laid out at full width so the tap target is stable once open; only its
 * painting is driven by [progress].
 */
@Composable
private fun DeleteAction(
    onClick: () -> Unit,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // Only tappable once fully uncovered, so a half-open row can't be deleted by
    // a tap the user meant for the row itself. Below that the node is dropped
    // from the semantics tree entirely — otherwise every closed row would offer
    // a screen reader a "delete thread" target it cannot activate.
    val active = progress > 0.99f
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(DELETE_REVEAL_WIDTH)
                .fillMaxHeight()
                .defaultMinSize(minHeight = 44.dp)
                .clickable(enabled = active, onClick = onClick)
                .then(
                    if (active) {
                        Modifier.semantics { contentDescription = "delete thread" }
                    } else {
                        Modifier.clearAndSetSemantics {}
                    },
                ),
        ) {
            Text(
                "delete",
                style = LemanType.value,
                color = LemanColors.danger,
                modifier = Modifier
                    .alpha(progress)
                    .scale(0.8f + progress * 0.2f),
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
        ThreadRow(
            title = "swiped open",
            preview = "delete button uncovered behind the row",
            stateLabel = "done",
            stateColor = LemanColors.textFaint,
            timeLabel = "18:02",
            dot = DotStyle.Hollow,
            unread = false,
            pinned = false,
            onOpen = {},
            onTogglePin = {},
            deleteRevealed = true,
        )
        ThreadRow(
            title = "delete that failed",
            preview = "server said no",
            stateLabel = "done",
            stateColor = LemanColors.textFaint,
            timeLabel = "17:30",
            dot = DotStyle.Hollow,
            unread = false,
            pinned = false,
            onOpen = {},
            onTogglePin = {},
            deleteFailed = true,
        )
    }
}
