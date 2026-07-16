package net.liquidx.leman.data.repo

/** First line, trimmed, ellipsized at [max] — shared by titles and previews. */
internal fun String.snippet(max: Int): String {
    val firstLine = lineSequence().firstOrNull()?.trim().orEmpty()
    return if (firstLine.length <= max) firstLine else firstLine.take(max - 1) + "…"
}
