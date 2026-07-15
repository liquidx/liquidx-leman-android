package net.liquidx.leman.ui.newthread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import net.liquidx.leman.ui.components.BlinkingCaret
import net.liquidx.leman.ui.components.LemanButton
import net.liquidx.leman.ui.components.LemanButtonKind
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.TitleRow
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairlineBorder

/** 2c — new thread. Minimal: composer box + full-width start button, no tab bar. */
@Composable
fun NewThreadScreen(
    state: NewThreadUiState,
    clock: String,
    connState: ConnState,
    onEvent: (NewThreadEvent) -> Unit,
    onCancel: () -> Unit,
) {
    ScreenFrame(
        statusRow = { StatusRow(clock, connState) },
        titleRow = {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            ) {
                Text("NEW THREAD", style = LemanType.display, color = LemanColors.textPrimary)
                Box(Modifier.weight(1f))
                Text(
                    "esc · cancel",
                    style = LemanType.meta,
                    color = LemanColors.textFaint,
                    modifier = Modifier.clickable(onClick = onCancel).padding(4.dp),
                )
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 140.dp)
                    .background(LemanColors.surface)
                    .hairlineBorder(LemanColors.accent)
                    .padding(14.dp),
            ) {
                if (state.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("> ", style = LemanType.bodyLarge, color = LemanColors.accent)
                        Text(
                            "what should ${state.agentName} do?",
                            style = LemanType.bodyLarge,
                            color = LemanColors.textFaint,
                        )
                        BlinkingCaret(Modifier.padding(start = 6.dp))
                    }
                }
                BasicTextField(
                    value = state.text,
                    onValueChange = { onEvent(NewThreadEvent.SetText(it)) },
                    textStyle = LemanType.bodyLarge.copy(color = LemanColors.textPrimary),
                    cursorBrush = SolidColor(LemanColors.accent),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 112.dp),
                )
            }
            Box(Modifier.padding(top = 12.dp)) {
                LemanButton(
                    label = "start thread ⏎",
                    onClick = { onEvent(NewThreadEvent.Start) },
                    kind = LemanButtonKind.Primary,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
