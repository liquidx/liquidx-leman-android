package net.liquidx.leman.domain.model

import java.io.IOException

/**
 * The one error taxonomy (spec 04). Everything thrown by OkHttp/serialization is
 * mapped into this at the data/remote boundary; nothing above the repository
 * layer sees raw exceptions.
 */
sealed interface ApiError {
    data class Network(val cause: IOException) : ApiError
    data object Timeout : ApiError
    data class Auth(val code: Int) : ApiError
    data class Server(val code: Int, val message: String?) : ApiError
    data class Client(val code: Int, val message: String?) : ApiError
    data class Protocol(val detail: String) : ApiError
    data object NotConfigured : ApiError
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Err)?.error
