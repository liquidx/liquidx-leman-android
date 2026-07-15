package net.liquidx.leman.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class HairlineSide { Top, Bottom, Start, End }

/**
 * Exact 1px hairline (spec 06): stroke width in *pixels*, not dp, so hairlines
 * never fatten on high-density screens.
 */
fun Modifier.hairline(
    side: HairlineSide,
    color: Color = LemanColors.hairline,
): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    when (side) {
        HairlineSide.Top -> drawLine(color, Offset(0f, 0.5f), Offset(w, 0.5f), 1f)
        HairlineSide.Bottom -> drawLine(color, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1f)
        HairlineSide.Start -> drawLine(color, Offset(0.5f, 0f), Offset(0.5f, h), 1f)
        HairlineSide.End -> drawLine(color, Offset(w - 0.5f, 0f), Offset(w - 0.5f, h), 1f)
    }
}

/** 1px border on all sides, in pixels (used instead of Border which is dp-based). */
fun Modifier.hairlineBorder(color: Color = LemanColors.hairline): Modifier = drawBehind {
    drawRect(color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
}

/**
 * The accent glow — box-shadow doesn't exist in Compose (spec 06):
 * `0 0 8px rgba(107,160,216,.6)` drawn as a blurred rect behind content.
 */
fun Modifier.glow(
    color: Color = LemanColors.accent,
    radius: Dp = 8.dp,
    alpha: Float = 0.6f,
): Modifier = drawBehind {
    val blurPx = radius.toPx()
    if (blurPx <= 0f) return@drawBehind
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = color.copy(alpha = alpha).toArgb()
        frameworkPaint.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvas(
    block: (androidx.compose.ui.graphics.Canvas) -> Unit,
) = drawContext.canvas.let(block)

/**
 * Flat pressed-state overlay `rgba(255,255,255,.04)` — mechanical, instant, no
 * ripple animation (spec 06). Installed as the app-wide [LocalIndication].
 */
object LemanIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressOverlayNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()

    private class PressOverlayNode(
        private val interactionSource: InteractionSource,
    ) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val now = when (interaction) {
                        is PressInteraction.Press -> true
                        is PressInteraction.Release, is PressInteraction.Cancel -> false
                        else -> pressed
                    }
                    if (now != pressed) {
                        pressed = now
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(LemanColors.pressedOverlay)
        }
    }
}
