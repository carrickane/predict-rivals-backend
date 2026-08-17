package com.predictrivals.plugins

import com.predictrivals.common.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException.BadRequest> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<ApiException.Unauthorized> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(cause.message ?: "Unauthorized"))
        }
        exception<ApiException.Forbidden> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(cause.message ?: "Forbidden"))
        }
        exception<ApiException.NotFound> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        exception<ApiException.Conflict> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        // Malformed JSON or a missing/mistyped required field (e.g. wrong key name) is a client
        // input error, not a server fault — without this, it falls through to the 500 handler
        // below and looks like a crash.
        exception<ContentConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
}
