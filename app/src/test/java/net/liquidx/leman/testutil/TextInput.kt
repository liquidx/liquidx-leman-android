package net.liquidx.leman.testutil

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Simulates the user replacing the field's text. Outside composition nothing
 * flushes snapshot writes, so `snapshotFlow` observers (e.g. the ViewModel
 * persistence collectors) only see the edit after [Snapshot.sendApplyNotifications].
 */
fun TextFieldState.type(text: String) {
    setTextAndPlaceCursorAtEnd(text)
    Snapshot.sendApplyNotifications()
}
