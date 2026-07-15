package net.liquidx.leman.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.ui.markdown.DiffLineKind
import net.liquidx.leman.ui.markdown.classifyDiffLine
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline
import net.liquidx.leman.ui.theme.hairlineBorder

/**
 * Code / diff block (spec 05): surface bg, hairline border, no radius; header
 * row with filename + copy when a filename is present; body scrolls
 * horizontally, never wraps; diff mode colors by line prefix.
 */
@Composable
fun CodeBlock(block: AgentBlock.Code, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LemanColors.surface)
            .hairlineBorder(LemanColors.hairline),
    ) {
        if (block.filename != null) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .hairline(HairlineSide.Bottom, LemanColors.hairline)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    block.filename.orEmpty(),
                    style = LemanType.micro,
                    color = LemanColors.textFaint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (copied) "copied" else "copy",
                    style = LemanType.micro,
                    color = LemanColors.accent,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(block.text))
                        copied = true
                    },
                )
            }
        }
        val body = remember(block.text, block.isDiff) { codeBody(block) }
        Text(
            body,
            style = LemanType.code,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

private fun codeBody(block: AgentBlock.Code): AnnotatedString {
    val text = block.text.trimEnd('\n')
    if (!block.isDiff) return AnnotatedString(text)
    return buildAnnotatedString {
        text.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val color = when (classifyDiffLine(line)) {
                DiffLineKind.Added -> LemanColors.diffGreen
                DiffLineKind.Removed -> LemanColors.danger
                DiffLineKind.Hunk -> LemanColors.textFaint
                DiffLineKind.Context -> Color(0xFFC9C9D0)
            }
            withStyle(SpanStyle(color = color)) { append(line) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A)
@Composable
private fun CodeBlockPreview() {
    Column(modifier = Modifier.padding(12.dp)) {
        CodeBlock(
            AgentBlock.Code(
                filename = "ci/pipeline.yml",
                language = "diff",
                text = "@@ -12,7 +12,9 @@\n jobs:\n-  test:\n-    retries: 0\n+  test:\n+    retries: 2\n+    timeout: 30m",
                isDiff = true,
            ),
        )
    }
}
