package net.liquidx.leman.domain.model

/**
 * Rich render blocks derived client-side from an agent turn's markdown by the
 * post-processor (spec 05). The renderer consumes only this model.
 */
sealed interface AgentBlock {
    data class Prose(val markdown: String) : AgentBlock

    data class Code(
        val filename: String?,
        val language: String?,
        val text: String,
        val isDiff: Boolean,
    ) : AgentBlock

    data class TaskList(
        val title: String?,
        val counter: String?, // "BOOKING · 2/4"
        val items: List<TaskItem>,
    ) : AgentBlock

    data class Actions(val buttons: List<ActionButton>) : AgentBlock

    data class Collapsible(val summary: String, val body: String) : AgentBlock

    data class OptionTable(val rows: List<OptionRow>) : AgentBlock
}

enum class TaskItemState { Done, Active, Pending }

data class TaskItem(val label: String, val state: TaskItemState)

enum class ActionKind { Primary, Neutral, Dismiss }

data class ActionButton(val label: String, val payload: String, val kind: ActionKind)

data class OptionRow(val title: String, val value: String?, val detail: String?)
