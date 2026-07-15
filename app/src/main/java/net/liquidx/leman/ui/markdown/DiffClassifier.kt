package net.liquidx.leman.ui.markdown

enum class DiffLineKind { Added, Removed, Hunk, Context }

/** Line-prefix coloring for diff blocks (spec 05). */
fun classifyDiffLine(line: String): DiffLineKind = when {
    line.startsWith("@@") -> DiffLineKind.Hunk
    line.startsWith("+") -> DiffLineKind.Added
    line.startsWith("-") -> DiffLineKind.Removed
    else -> DiffLineKind.Context
}

/** Heuristic for unfenced-language diffs: majority of lines carry diff prefixes. */
fun looksLikeDiff(text: String): Boolean {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    val marked = lines.count { classifyDiffLine(it) != DiffLineKind.Context }
    return marked * 2 > lines.size
}
