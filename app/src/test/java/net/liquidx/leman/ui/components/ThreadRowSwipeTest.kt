package net.liquidx.leman.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.height
import net.liquidx.leman.ui.theme.LemanColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the swipe gesture itself, which the ViewModel tests cannot reach: the
 * drag-to-reveal, the tap targets that change meaning while open, and the
 * interaction with the enclosing LazyColumn's vertical scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h788dp-320dpi")
class ThreadRowSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Row(
        title: String = "swipe me",
        revealed: Boolean = false,
        onOpen: () -> Unit = {},
        onReveal: () -> Unit = {},
        onHide: () -> Unit = {},
        onConfirm: () -> Unit = {},
    ) {
        ThreadRow(
            title = title,
            preview = "preview",
            stateLabel = "done",
            stateColor = LemanColors.textFaint,
            timeLabel = "12:00",
            dot = DotStyle.Hollow,
            unread = false,
            pinned = false,
            onOpen = onOpen,
            onTogglePin = {},
            deleteRevealed = revealed,
            onRevealDelete = onReveal,
            onHideDelete = onHide,
            onConfirmDelete = onConfirm,
        )
    }

    @Test
    fun swipeLeft_revealsDelete() {
        var revealed = false
        compose.setContent {
            var state by remember { mutableStateOf(false) }
            revealed = state
            Row(revealed = state, onReveal = { state = true }, onHide = { state = false })
        }
        compose.onNodeWithText("swipe me").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("a left swipe should reveal the delete action", revealed)
    }

    @Test
    fun swipeRight_whenOpen_closesAgain() {
        var revealed = true
        compose.setContent {
            var state by remember { mutableStateOf(true) }
            revealed = state
            Row(revealed = state, onReveal = { state = true }, onHide = { state = false })
        }
        compose.onNodeWithText("swipe me").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue("swiping an open row back should close it", !revealed)
    }

    @Test
    fun tappingRevealedDeleteButton_confirms() {
        var confirmed = 0
        compose.setContent { Row(revealed = true, onConfirm = { confirmed++ }) }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("delete thread")).performClick()
        compose.waitForIdle()
        assertEquals("the revealed button should confirm the delete", 1, confirmed)
    }

    @Test
    fun deleteButton_isNotTappableWhileRowIsClosed() {
        compose.setContent { Row(revealed = false) }
        compose.waitForIdle()
        // Not merely disabled — absent, so no tap can reach it and no screen
        // reader can land on a target it could not activate.
        compose.onNode(hasContentDescription("delete thread")).assertDoesNotExist()
    }

    /** The only delete path reachable without performing a drag. */
    @Test
    fun rowExposesDeleteAsAnAccessibilityAction() {
        var confirmed = 0
        compose.setContent { Row(revealed = false, onConfirm = { confirmed++ }) }
        compose.waitForIdle()
        // The action sits on the row's outer slot, which is not the node that
        // onNodeWithText resolves to, so walk the tree for it.
        fun customActionsIn(node: SemanticsNode): List<CustomAccessibilityAction> =
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty() +
                node.children.flatMap { customActionsIn(it) }

        val delete = customActionsIn(compose.onRoot().fetchSemanticsNode())
            .firstOrNull { it.label == "delete thread" }
        assertTrue("the row should expose a delete custom action", delete != null)
        delete!!.action?.invoke()
        assertEquals("the custom action should delete directly", 1, confirmed)
    }

    @Test
    fun tappingRowBody_whileOpen_closesInsteadOfOpeningThread() {
        var opened = 0
        var hidden = 0
        compose.setContent {
            Row(revealed = true, onOpen = { opened++ }, onHide = { hidden++ })
        }
        compose.waitForIdle()
        compose.onNodeWithText("swipe me").performClick()
        compose.waitForIdle()
        assertEquals("an open row must not navigate", 0, opened)
        assertEquals("tapping an open row should close it", 1, hidden)
    }

    @Test
    fun tappingRowBody_whileClosed_opensThread() {
        var opened = 0
        compose.setContent { Row(revealed = false, onOpen = { opened++ }) }
        compose.onNodeWithText("swipe me").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    /**
     * Guards the reason DeleteAction uses matchParentSize rather than
     * fillMaxHeight: inside a LazyColumn the height constraint is infinite, so
     * fillMaxHeight is a no-op and leaves dead strips above and below the button.
     */
    @Test
    fun revealedDeleteButton_fillsFullRowHeight() {
        compose.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(3) { Row(title = "row $it", revealed = it == 0) }
            }
        }
        compose.waitForIdle()
        // Only the revealed row contributes a delete node; closed rows drop
        // theirs from the semantics tree, so this query is unambiguous.
        val button = compose.onNode(hasContentDescription("delete thread"))
            .getUnclippedBoundsInRoot()
        // Rows are ~67dp tall here; a button clamped to its 44dp minimum would
        // leave dead strips top and bottom.
        assertTrue(
            "the delete target should span the whole row, not just its 44dp minimum",
            button.height.value > 60f,
        )
    }

    @Test
    fun closedRows_exposeNoDeleteTargetToAccessibility() {
        compose.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(3) { Row(title = "row $it", revealed = false) }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("delete thread")).assertCountEquals(0)
    }

    /**
     * The failure that would matter most in the hand: if horizontal drag
     * detection didn't yield to the list's vertical scroll, an ordinary flick
     * through the list would arm a destructive action under the user's thumb.
     */
    @Test
    fun verticalScroll_doesNotRevealDelete() {
        var revealCount = 0
        compose.setContent {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(40) { index ->
                    Row(title = "row $index", onReveal = { revealCount++ })
                }
            }
        }
        // Swipe at the root, not a named row: rows scroll out from under the
        // finger, and re-querying a specific one would fail before the assert.
        repeat(3) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertEquals("scrolling the list must never reveal a delete", 0, revealCount)
    }
}
