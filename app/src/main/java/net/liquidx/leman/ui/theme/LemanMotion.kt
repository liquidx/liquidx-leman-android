package net.liquidx.leman.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/** Sharp and mechanical, no bounce (spec 06 / design system). */
object LemanMotion {
    /** Rows fade up 7px over .5s; stagger .06s per index, ≤8 items. */
    val riseIn: EnterTransition =
        fadeIn(tween(500)) + slideInVertically(tween(500)) { 7 }

    fun riseInStaggered(index: Int): EnterTransition {
        val delay = (index.coerceAtMost(8)) * 60
        return fadeIn(tween(500, delayMillis = delay)) +
            slideInVertically(tween(500, delayMillis = delay)) { 7 }
    }

    /** Trace/collapsible expansion. */
    val riseInFast: EnterTransition =
        fadeIn(tween(300)) + slideInVertically(tween(300)) { 7 }

    val fadeOutFast = fadeOut(tween(150))

    /** Progress bars: scaleX from left. */
    val barDraw = tween<Float>(800, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))

    /** Screen transitions are cuts or fades ≤150ms (spec 01). */
    val screenFadeMillis = 150

    /** pulse: scale .5→2.6 / alpha .8→0, 2s ease-out loop — running dots. */
    const val pulseDurationMillis = 2000

    /** caret: 1s steps(1) on/off — composer & streaming cursor. */
    const val caretPeriodMillis = 1000

    /** Toggle knob translation, 120ms linear. */
    const val toggleKnobMillis = 120
}
