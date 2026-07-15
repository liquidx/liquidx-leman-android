package net.liquidx.leman.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.liquidx.leman.R

/** Spline Sans Mono everywhere — one family, three weights (spec 06). */
val SplineSansMono = FontFamily(
    Font(R.font.spline_sans_mono_regular, FontWeight.W400),
    Font(R.font.spline_sans_mono_medium, FontWeight.W500),
    Font(R.font.spline_sans_mono_semibold, FontWeight.W600),
)

/**
 * Type scale (spec 06). `includeFontPadding = false` everywhere so the mono
 * grid matches the mocks; letter-spacing in em units.
 */
object LemanType {
    private val base = TextStyle(
        fontFamily = SplineSansMono,
        fontWeight = FontWeight.W400,
        color = LemanColors.textPrimary,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    /** 23sp/600/+0.05em — screen titles, rendered uppercase by callers. */
    val display = base.copy(fontSize = 23.sp, fontWeight = FontWeight.W600, letterSpacing = 0.05.em)

    /** 14sp — thread titles. */
    val value = base.copy(fontSize = 14.sp)

    /** 12sp / lh 1.7 — agent turn bodies, markdown prose. */
    val body = base.copy(fontSize = 12.sp, lineHeight = 12.sp * 1.7)

    /** 12sp / lh 1.65 — user turn bodies. */
    val bodyUser = base.copy(fontSize = 12.sp, lineHeight = 12.sp * 1.65)

    /** 13sp — the 2c composer. */
    val bodyLarge = base.copy(fontSize = 13.sp, lineHeight = 13.sp * 1.65)

    /** 10sp/+0.16em uppercase — section headers. */
    val label = base.copy(fontSize = 10.sp, letterSpacing = 0.16.em, color = LemanColors.labelHeader)

    /** 10sp — previews, state tokens, trace summaries. */
    val meta = base.copy(fontSize = 10.sp)

    /** 9sp/+0.1em — status row, tab bar, timestamps, speaker tags. */
    val micro = base.copy(fontSize = 9.sp, letterSpacing = 0.1.em)

    /** 10.5sp / lh 1.75 — code block bodies (spec 05). */
    val code = base.copy(fontSize = 10.5.sp, lineHeight = 10.5.sp * 1.75, color = Color(0xFFC9C9D0))

    /** 9sp/+0.14em — YOU / agent-name turn tags (2b). */
    val turnTag = base.copy(fontSize = 9.sp, letterSpacing = 0.14.em)
}
