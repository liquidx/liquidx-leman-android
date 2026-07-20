package net.liquidx.leman.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.components.EmptyLine
import net.liquidx.leman.ui.components.LemanButton
import net.liquidx.leman.ui.components.LemanButtonKind
import net.liquidx.leman.ui.components.LemanToggle
import net.liquidx.leman.ui.components.PromptField
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairlineBorder

/** 2f — add/edit one job. Minimal form, no tab bar (jobs-tab design). */
@Composable
fun JobEditScreen(
    state: JobEditUiState,
    clock: String,
    connState: ConnState,
    onEvent: (JobEditEvent) -> Unit,
    onCancel: () -> Unit,
    nameState: TextFieldState = rememberTextFieldState(),
    scheduleState: TextFieldState = rememberTextFieldState(),
    promptState: TextFieldState = rememberTextFieldState(),
) {
    ScreenFrame(
        statusRow = { StatusRow(clock, connState) },
        titleRow = {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            ) {
                Text(
                    if (state.isNew) "NEW JOB" else "EDIT JOB",
                    style = LemanType.display,
                    color = LemanColors.textPrimary,
                )
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
        if (state.missing) {
            EmptyLine("job no longer exists on the gateway")
            return@ScreenFrame
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            FieldLabel("name")
            PromptField(state = nameState, placeholder = "what to call it")

            FieldLabel("schedule")
            PromptField(state = scheduleState, placeholder = "0 7 * * *")
            Text(
                "cron `0 7 * * *` · `every 2h` · one-shot `30m` or iso time",
                style = LemanType.meta,
                color = LemanColors.textFaint,
                modifier = Modifier.padding(top = 4.dp),
            )

            FieldLabel("prompt")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .background(LemanColors.surface)
                    .hairlineBorder(LemanColors.hairlineStrong)
                    .padding(12.dp),
            ) {
                if (promptState.text.isEmpty()) {
                    Text(
                        "what should the agent do on each run?",
                        style = LemanType.body,
                        color = LemanColors.textFaint,
                    )
                }
                BasicTextField(
                    state = promptState,
                    textStyle = LemanType.body.copy(color = LemanColors.textPrimary),
                    cursorBrush = SolidColor(LemanColors.accent),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 96.dp),
                )
            }

            if (!state.isNew) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) {
                    Text(
                        "enabled",
                        style = LemanType.body,
                        color = LemanColors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    LemanToggle(state.enabled, { onEvent(JobEditEvent.SetEnabled(it)) })
                }
            }

            state.error?.let {
                Text(
                    "▪ $it",
                    style = LemanType.meta.copy(fontSize = 11.sp),
                    color = LemanColors.danger,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Box(Modifier.padding(top = 14.dp)) {
                LemanButton(
                    label = if (state.isNew) "create job ⏎" else "save changes ⏎",
                    onClick = { onEvent(JobEditEvent.Save) },
                    kind = LemanButtonKind.Primary,
                    enabled = !state.busy &&
                        nameState.text.isNotBlank() &&
                        scheduleState.text.isNotBlank() &&
                        promptState.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!state.isNew) {
                Box(Modifier.padding(top = 10.dp, bottom = 24.dp)) {
                    if (state.confirmingDelete) {
                        Row {
                            LemanButton(
                                label = "really delete",
                                onClick = { onEvent(JobEditEvent.ConfirmDelete) },
                                kind = LemanButtonKind.Danger,
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                            Box(Modifier.padding(4.dp))
                            LemanButton(
                                label = "keep it",
                                onClick = { onEvent(JobEditEvent.DismissDelete) },
                                kind = LemanButtonKind.Dismiss,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        LemanButton(
                            label = "delete job",
                            onClick = { onEvent(JobEditEvent.RequestDelete) },
                            kind = LemanButtonKind.Danger,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = LemanType.label,
        color = LemanColors.textFaint,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
