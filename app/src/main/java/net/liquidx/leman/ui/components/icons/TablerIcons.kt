package net.liquidx.leman.ui.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Exactly two Tabler icons (MIT), tab bar only (spec 06): 24dp viewport,
 * stroke 1.5, butt caps, miter joins, drawn at 16dp. License note in NOTICE.
 */
object TablerIcons {
    val Message: ImageVector by lazy {
        tablerIcon(
            "TablerMessage",
            "M8 9h8",
            "M8 13h6",
            "M18 4a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-5l-5 4v-4h-2a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3h12z",
        )
    }

    val Settings: ImageVector by lazy {
        tablerIcon(
            "TablerSettings",
            "M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }

    /** Debug builds add a third, danger-colored tab (spec 08). Tabler "bug". */
    val Bug: ImageVector by lazy {
        tablerIcon(
            "TablerBug",
            "M9 9v-1a3 3 0 0 1 6 0v1",
            "M8 9h8a6 6 0 0 1 1 3v3a5 5 0 0 1 -10 0v-3a6 6 0 0 1 1 -3",
            "M3 13h4",
            "M17 13h4",
            "M12 20v-6",
            "M4 19l3.35 -2",
            "M20 19l-3.35 -2",
            "M4 7l3.75 2.4",
            "M20 7l-3.75 2.4",
        )
    }

    private fun tablerIcon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        return builder.build()
    }
}
