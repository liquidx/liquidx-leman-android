package net.liquidx.leman.ui.theme

import android.content.Context
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import net.liquidx.leman.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Everything except the two tab icons is a text glyph (spec 06). Silent
 * fallback to a serif glyph would be invisible in code review — assert every
 * design glyph measures non-zero with the bundled font.
 */
@RunWith(RobolectricTestRunner::class)
class GlyphCoverageTest {

    private val designGlyphs =
        listOf("▪", "◆", "◇", "▸", "▾", ">", "‹", "✳", "▲", "●", "⌬", "◉", "⏎")

    @Test
    fun bundledFont_rendersEveryDesignGlyph_nonZeroWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val typeface = ResourcesCompat.getFont(context, R.font.spline_sans_mono_regular)
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = 16f
        }
        val zeroWidth = designGlyphs.filter { paint.measureText(it) <= 0f }
        assertTrue("glyphs with zero measured width: $zeroWidth", zeroWidth.isEmpty())
    }
}
