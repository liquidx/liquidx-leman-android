package net.liquidx.leman.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.components.icons.TablerIcons
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.glow
import net.liquidx.leman.ui.theme.hairline
import net.liquidx.leman.ui.theme.lemanScreenBackground

/** Status row (spec 04/06): clock left, `▪ hermes · state` right, 9sp micro. */
@Composable
fun StatusRow(clock: String, connState: ConnState, modifier: Modifier = Modifier) {
    val (dotColor, label, dotGlows) = when (connState) {
        is ConnState.Online -> Triple(LemanColors.accent, "hermes · ${connState.version}", true)
        ConnState.Checking -> Triple(LemanColors.warn, "hermes · connecting…", false)
        is ConnState.Offline -> Triple(LemanColors.textFaint, "hermes · offline", false)
        is ConnState.Unauthorized -> Triple(LemanColors.danger, "hermes · auth failed", false)
        ConnState.NotConfigured -> Triple(LemanColors.textFaint, "hermes · set up in config", false)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clock, style = LemanType.micro, color = LemanColors.textFaint)
        Box(Modifier.weight(1f))
        Text(
            "▪",
            style = LemanType.micro,
            color = dotColor,
            modifier = if (dotGlows) Modifier.glow(dotColor, 4.dp, 0.6f) else Modifier,
        )
        Text(" $label", style = LemanType.micro, color = LemanColors.textFaint)
    }
}

/** Title row: display type left, 10sp meta readout right (accent spans allowed). */
@Composable
fun TitleRow(title: String, readout: AnnotatedString? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title.uppercase(), style = LemanType.display, color = LemanColors.textPrimary)
        Box(Modifier.weight(1f))
        readout?.let { Text(it, style = LemanType.meta, color = LemanColors.textFaint) }
    }
}

/** Tick strip (spec 06): 9dp tall, 1px ticks every 24dp on a hairline baseline. */
@Composable
fun TickStrip(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .height(9.dp),
    ) {
        val step = 24.dp.toPx()
        drawLine(
            LemanColors.hairline,
            Offset(0f, size.height - 0.5f),
            Offset(size.width, size.height - 0.5f),
            1f,
        )
        var x = 0f
        while (x <= size.width) {
            drawLine(LemanColors.hairline, Offset(x + 0.5f, 0f), Offset(x + 0.5f, size.height), 1f)
            x += step
        }
    }
}

data class LemanTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
)

val ThreadsTab = LemanTab("threads", "threads", TablerIcons.Message)
val ConfigTab = LemanTab("config", "config", TablerIcons.Settings)

/** Tab bar (spec 06): 16dp Tabler icon + 9sp label; active = accent + 2dp top rule. */
@Composable
fun LemanTabBar(
    tabs: List<LemanTab>,
    activeId: String,
    onSelect: (LemanTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hairline(HairlineSide.Top, LemanColors.hairline),
    ) {
        tabs.forEach { tab ->
            val active = tab.id == activeId
            val tint = when {
                tab.danger -> LemanColors.danger
                active -> LemanColors.accent
                else -> LemanColors.tabInactive
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (active) Modifier.hairline(HairlineSide.Top, LemanColors.accent) else Modifier,
                    )
                    .clickable { onSelect(tab) }
                    .padding(top = 10.dp, bottom = 12.dp),
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = tint)
                Text(
                    tab.label,
                    style = LemanType.micro,
                    color = tint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Screen scaffold (spec 06): status/title/tick chrome, scrollable content,
 * pinned bottom slots, edge-to-edge insets. 18dp horizontal padding is applied
 * by the individual rows so full-bleed children (hairlines) stay possible.
 */
@Composable
fun ScreenFrame(
    modifier: Modifier = Modifier,
    statusRow: @Composable () -> Unit = {},
    titleRow: @Composable () -> Unit = {},
    showTickStrip: Boolean = true,
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .lemanScreenBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        statusRow()
        titleRow()
        if (showTickStrip) TickStrip()
        Column(Modifier.weight(1f)) { content() }
        bottom()
    }
}

/** Centered faint 11sp line — empty states and inline errors (spec 04). */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = LemanType.meta.copy(fontSize = 11.sp),
            color = LemanColors.textFaint,
        )
    }
}

/** `LABEL · count ——— right` with the hairline filling the gap (spec 06). */
@Composable
fun SectionHeader(
    label: String,
    count: Int? = null,
    right: String? = null,
    rightAccent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(label.uppercase(), style = LemanType.label)
        count?.let {
            Text(" · ", style = LemanType.label, color = LemanColors.textFaint)
            Text("$it", style = LemanType.label, color = LemanColors.textSecondary)
        }
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(1.dp)
                .hairline(HairlineSide.Top, LemanColors.hairline),
        )
        right?.let {
            Text(
                it,
                style = LemanType.label.copy(letterSpacing = LemanType.meta.letterSpacing),
                color = if (rightAccent) LemanColors.accent else LemanColors.textFaint,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A, widthDp = 380)
@Composable
private fun ChromePreview() {
    Column {
        StatusRow("09:41", ConnState.Online("0.18.0"))
        TitleRow("threads", AnnotatedString("7 · 1 running"))
        TickStrip()
        SectionHeader("pinned", 2, "jul 14")
        EmptyLine("no threads match \"query\"")
        LemanTabBar(listOf(ThreadsTab, ConfigTab), "threads", {})
    }
}
