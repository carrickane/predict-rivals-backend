package com.predictrivals.common

sealed class ApiException(message: String) : RuntimeException(message) {
    class BadRequest(message: String) : ApiException(message)
    class Unauthorized(message: String = "Unauthorized") : ApiException(message)
    class Forbidden(message: String = "Forbidden") : ApiException(message)
    class NotFound(message: String) : ApiException(message)
    class Conflict(message: String) : ApiException(message)
}
