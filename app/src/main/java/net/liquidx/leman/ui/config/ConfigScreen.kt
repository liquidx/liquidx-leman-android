package net.liquidx.leman.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Settings
import net.liquidx.leman.ui.components.GlyphTile
import net.liquidx.leman.ui.components.LemanButton
import net.liquidx.leman.ui.components.LemanButtonKind
import net.liquidx.leman.ui.components.LemanTab
import net.liquidx.leman.ui.components.LemanTabBar
import net.liquidx.leman.ui.components.LemanToggle
import net.liquidx.leman.ui.components.PromptField
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.SectionHeader
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.TitleRow
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType

/** 2d — config. */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    clock: String,
    tabs: List<LemanTab>,
    appVersion: String,
    onEvent: (ConfigEvent) -> Unit,
    onRevealRequested: () -> Unit,
    onExport: () -> Unit,
    onOpenThreads: () -> Unit,
    onSelectTab: (LemanTab) -> Unit = {},
    serverUrlState: TextFieldState = rememberTextFieldState(),
    apiKeyState: TextFieldState = rememberTextFieldState(),
    agentNameState: TextFieldState = rememberTextFieldState(),
) {
    ScreenFrame(
        statusRow = { StatusRow(clock, state.connState) },
        titleRow = { TitleRow("config", AnnotatedString("leman v$appVersion")) },
        bottom = {
            LemanTabBar(tabs, activeId = "config", onSelect = {
                when (it.id) {
                    "threads" -> onOpenThreads()
                    "config" -> Unit
                    else -> onSelectTab(it)
                }
            })
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ---- SERVER --------------------------------------------------
            SectionHeader(
                "server",
                right = when (state.connState) {
                    is ConnState.Online -> "▪ connected"
                    ConnState.Checking -> "▪ connecting…"
                    is ConnState.Unauthorized -> "▪ auth failed"
                    else -> "▪ offline"
                },
                rightAccent = state.connState is ConnState.Online,
            )
            FieldLabel("hermes agent url")
            Box(Modifier.padding(horizontal = 18.dp)) {
                PromptField(
                    state = serverUrlState,
                    placeholder = Settings.DEFAULT_SERVER_URL,
                    hint = "⏎ save",
                    onSubmit = { onEvent(ConfigEvent.SaveServerUrl) },
                )
            }
            state.urlError?.let { Caption(it, danger = true) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                LemanButton("test connection", { onEvent(ConfigEvent.TestConnection) })
                Spacer(Modifier.padding(6.dp))
                when (val t = state.testResult) {
                    TestConnectionState.Idle -> {}
                    TestConnectionState.Testing -> CaptionText("testing…", LemanColors.textFaint)
                    is TestConnectionState.Ok -> CaptionText("${t.platform} · v${t.version}", LemanColors.textFaint)
                    is TestConnectionState.Unreachable -> CaptionText("▪ unreachable · ${t.detail}", LemanColors.warn)
                    TestConnectionState.AuthFailed -> CaptionText("▪ auth failed · check api key", LemanColors.danger)
                }
            }
            SectionGap()

            // ---- AUTH ----------------------------------------------------
            SectionHeader("auth")
            FieldLabel("api key")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(
                    state.revealedKey ?: state.apiKeyMasked ?: "no key set",
                    style = LemanType.meta.copy(fontSize = 11.sp, letterSpacing = 0.14.em),
                    color = LemanColors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (state.revealedKey != null) "hide" else "reveal",
                    style = LemanType.meta,
                    color = LemanColors.accent,
                    modifier = Modifier
                        .clickable {
                            if (state.revealedKey != null) onEvent(ConfigEvent.HideKey) else onRevealRequested()
                        }
                        .padding(6.dp),
                )
            }
            Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                PromptField(
                    state = apiKeyState,
                    placeholder = "paste api key",
                    hint = "⏎ save",
                    onSubmit = { onEvent(ConfigEvent.SaveApiKey) },
                )
            }
            ToggleRow("unlock with biometrics", state.settings.biometricUnlock) {
                onEvent(ConfigEvent.SetBiometricUnlock(it))
            }
            Caption("key stored in secure enclave · never synced")
            SectionGap()

            // ---- AGENT IDENTITY -------------------------------------------
            SectionHeader("agent identity", right = "default profile")
            FieldLabel("agent name")
            Box(Modifier.padding(horizontal = 18.dp)) {
                PromptField(
                    state = agentNameState,
                    placeholder = Settings.DEFAULT_AGENT_NAME,
                )
            }
            FieldLabel("avatar")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Settings.GLYPH_CHOICES.forEach { glyph ->
                    GlyphTile(
                        glyph = glyph,
                        selected = glyph == state.settings.agentGlyph,
                        onClick = { onEvent(ConfigEvent.SelectGlyph(glyph)) },
                    )
                }
            }
            Caption("shown in thread headers & turn labels · threads with their own profile override this")
            SectionGap()

            // ---- DISPLAY ---------------------------------------------------
            SectionHeader("display")
            ToggleRow("expand traces by default", state.settings.expandTracesByDefault) {
                onEvent(ConfigEvent.SetExpandTraces(it))
            }
            ToggleRow("show tool args in traces", state.settings.showToolArgs) {
                onEvent(ConfigEvent.SetShowToolArgs(it))
            }
            SectionGap()

            // ---- DATA ------------------------------------------------------
            SectionHeader("data")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                LemanButton("export threads", onExport)
                LemanButton(
                    if (state.confirmClearArmed) "tap again to confirm" else "clear all threads",
                    { onEvent(ConfigEvent.ClearAllThreads) },
                    kind = LemanButtonKind.Danger,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = LemanType.meta.copy(fontSize = 11.sp),
        color = LemanColors.textSecondary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

@Composable
private fun Caption(text: String, danger: Boolean = false) {
    Text(
        text,
        style = LemanType.meta,
        color = if (danger) LemanColors.danger else LemanColors.textFaint,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

@Composable
private fun CaptionText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = LemanType.meta, color = color)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = LemanType.meta.copy(fontSize = 11.sp),
            color = LemanColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        LemanToggle(checked, onChange)
    }
}

@Composable
private fun SectionGap() {
    Box(Modifier.height(30.dp))
}
