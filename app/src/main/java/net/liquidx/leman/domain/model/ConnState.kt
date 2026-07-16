package net.liquidx.leman.domain.model

/**
 * "Connection" means: can we reach the gateway (spec 02). There is no persistent
 * app-wide socket; this reflects the health probe plus auth state.
 */
sealed interface ConnState {
    data object NotConfigured : ConnState
    data object Checking : ConnState
    data class Online(val version: String) : ConnState
    data class Offline(val error: ApiError) : ConnState
    data class Unauthorized(val error: ApiError) : ConnState

    /** Gateway is reachable but doesn't implement the Sessions API (spec §5). */
    data class Unsupported(val version: String) : ConnState
}
