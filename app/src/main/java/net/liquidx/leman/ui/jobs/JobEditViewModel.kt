package net.liquidx.leman.ui.jobs

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.JobsRepository
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult

data class JobEditUiState(
    val isNew: Boolean = true,
    /** Edit target not in the store even after a refresh (deleted elsewhere). */
    val missing: Boolean = false,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    /** Delete is armed — the next tap actually deletes. */
    val confirmingDelete: Boolean = false,
    /** Inline API error (e.g. the server's invalid-schedule usage text). */
    val error: String? = null,
)

sealed interface JobEditEvent {
    data object Save : JobEditEvent
    data class SetEnabled(val enabled: Boolean) : JobEditEvent
    data object RequestDelete : JobEditEvent
    data object ConfirmDelete : JobEditEvent
    data object DismissDelete : JobEditEvent
}

/** 2f — add/edit one job (jobs-tab design). `jobId == null` is the add form. */
class JobEditViewModel(
    private val repo: JobsRepository,
    private val jobId: String?,
) : ViewModel() {

    val nameState = TextFieldState()
    val scheduleState = TextFieldState()
    val promptState = TextFieldState()

    private val _state = MutableStateFlow(JobEditUiState(isNew = jobId == null))
    val state: StateFlow<JobEditUiState> = _state

    /** One-shot: saved or deleted → pop back to the list. */
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()

    init {
        if (jobId != null) {
            viewModelScope.launch {
                val job = repo.job(jobId) ?: run {
                    repo.refresh()
                    repo.job(jobId)
                }
                if (job == null) {
                    _state.value = _state.value.copy(missing = true)
                } else {
                    nameState.setTextAndPlaceCursorAtEnd(job.name)
                    scheduleState.setTextAndPlaceCursorAtEnd(job.scheduleDisplay)
                    promptState.setTextAndPlaceCursorAtEnd(job.prompt)
                    _state.value = _state.value.copy(enabled = job.enabled)
                }
            }
        }
    }

    fun onEvent(event: JobEditEvent) {
        when (event) {
            JobEditEvent.Save -> save()
            is JobEditEvent.SetEnabled -> setEnabled(event.enabled)
            JobEditEvent.RequestDelete -> _state.value = _state.value.copy(confirmingDelete = true)
            JobEditEvent.DismissDelete -> _state.value = _state.value.copy(confirmingDelete = false)
            JobEditEvent.ConfirmDelete -> delete()
        }
    }

    private fun save() {
        val name = nameState.text.toString().trim()
        val schedule = scheduleState.text.toString().trim()
        val prompt = promptState.text.toString().trim()
        if (name.isEmpty() || schedule.isEmpty() || prompt.isEmpty() || _state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result =
                if (jobId == null) repo.create(name, prompt, schedule)
                else repo.save(jobId, name, prompt, schedule)
            when (result) {
                is ApiResult.Ok -> _done.tryEmit(Unit)
                is ApiResult.Err ->
                    _state.value = _state.value.copy(busy = false, error = describe(result.error))
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        val id = jobId ?: return
        if (_state.value.busy) return
        // Optimistic knob, authoritative echo: revert on failure.
        val previous = _state.value.enabled
        _state.value = _state.value.copy(enabled = enabled, error = null)
        viewModelScope.launch {
            when (val result = repo.setEnabled(id, enabled)) {
                is ApiResult.Ok -> _state.value = _state.value.copy(enabled = result.value.enabled)
                is ApiResult.Err ->
                    _state.value = _state.value.copy(enabled = previous, error = describe(result.error))
            }
        }
    }

    private fun delete() {
        val id = jobId ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = repo.delete(id)) {
                is ApiResult.Ok -> _done.tryEmit(Unit)
                is ApiResult.Err -> _state.value = _state.value.copy(
                    busy = false,
                    confirmingDelete = false,
                    error = describe(result.error),
                )
            }
        }
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.Server -> error.message ?: "server error · try again"
        is ApiError.Client -> error.message ?: "request failed"
        is ApiError.Auth -> "auth failed · fix api key in config"
        ApiError.Timeout -> "timed out · try again"
        is ApiError.Network -> "offline · check connection"
        is ApiError.Protocol -> "bad response from gateway"
        ApiError.NotConfigured -> "set server url + api key in config"
    }
}
