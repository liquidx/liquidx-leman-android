package net.liquidx.leman.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.liquidx.leman.domain.model.AgentBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Links must be tappable in every turn kind that renders markdown — not just
 * agent prose (the goal: any web link in the chat opens on tap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h788dp-320dpi")
class ThreadViewLinkTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun userTurn_linkTap_firesCallback() {
        var opened: String? = null
        compose.setContent {
            UserTurn(
                markdown = "https://example.com/user",
                viaButton = false,
                failed = false,
                onLinkClick = { opened = it },
            )
        }
        compose.onNodeWithText("https://example.com/user").performClick()
        assertEquals("https://example.com/user", opened)
    }

    @Test
    fun systemTurn_linkTap_firesCallback() {
        var opened: String? = null
        compose.setContent {
            SystemTurn(
                markdown = "https://example.com/system",
                expanded = true,
                onToggle = {},
                onLinkClick = { opened = it },
            )
        }
        compose.onNodeWithText("https://example.com/system").performClick()
        assertEquals("https://example.com/system", opened)
    }

    @Test
    fun collapsibleBlock_linkTap_firesCallback() {
        var opened: String? = null
        compose.setContent {
            CollapsibleBlock(
                block = AgentBlock.Collapsible(
                    summary = "details",
                    body = "https://example.com/collapsed",
                ),
                onLinkClick = { opened = it },
            )
        }
        compose.onNodeWithText("details").performClick()
        compose.onNodeWithText("https://example.com/collapsed").performClick()
        assertEquals("https://example.com/collapsed", opened)
    }

    @Test
    fun agentTurn_collapsibleLinkTap_firesCallback() {
        var opened: String? = null
        compose.setContent {
            AgentTurn(
                agentName = "leman",
                blocks = listOf(
                    AgentBlock.Collapsible(summary = "more", body = "https://example.com/agent"),
                ),
                onLinkClick = { opened = it },
            )
        }
        compose.onNodeWithText("more").performClick()
        compose.onNodeWithText("https://example.com/agent").performClick()
        assertEquals("https://example.com/agent", opened)
    }
}
