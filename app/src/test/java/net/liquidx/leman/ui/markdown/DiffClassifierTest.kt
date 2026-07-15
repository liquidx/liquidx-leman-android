package net.liquidx.leman.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffClassifierTest {

    @Test
    fun classify_prefixesMapToKinds() {
        assertEquals(DiffLineKind.Added, classifyDiffLine("+    retries: 2"))
        assertEquals(DiffLineKind.Removed, classifyDiffLine("-    retries: 0"))
        assertEquals(DiffLineKind.Hunk, classifyDiffLine("@@ -12,7 +12,9 @@"))
        assertEquals(DiffLineKind.Context, classifyDiffLine(" jobs:"))
        assertEquals(DiffLineKind.Context, classifyDiffLine("plain"))
    }

    @Test
    fun looksLikeDiff_majorityPrefixedLines_true() {
        assertEquals(true, looksLikeDiff("-a\n+b\n+c\ncontext"))
        assertEquals(false, looksLikeDiff("fun x() = 1\nval y = 2"))
    }
}
