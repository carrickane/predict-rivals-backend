package com.predictrivals.live

import com.auth0.jwt.exceptions.JWTVerificationException
import com.predictrivals.common.ApiException
import com.predictrivals.common.JWT_CLAIM_USER_ID
import com.predictrivals.common.JwtService
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.json.Json

/**
 * The WebSocket handshake is a plain HTTP GET, so browsers can't attach an Authorization header
 * to it (the WebSocket API doesn't support custom headers) — the JWT is passed as a `token` query
 * parameter instead and verified manually here, rather than via the usual `authenticate(AUTH_JWT)`
 * plugin (which only reads the Authorization header).
 */
fun Route.liveRoutes(
    liveStateService: LiveStateService,
    liveHub: LiveHub,
    tournamentRepository: TournamentRepository,
    jwtService: JwtService,
) {
    authenticate(AUTH_JWT) {
        get("/api/tournaments/{tournamentId}/live") {
            val userId = call.principalUserId()
            val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                ?: throw ApiException.BadRequest("Invalid tournament id")
            if (!tournamentRepository.isMember(userId, tournamentId)) {
                throw ApiException.Forbidden("Join the tournament to view its live state")
            }
            call.respond(liveStateService.buildCurrentState(tournamentId))
        }
    }

    webSocket("/ws/tournaments/{tournamentId}/live") {
        val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
        val token = call.request.queryParameters["token"]

        if (tournamentId == null || token == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing tournamentId path segment or token query param"))
            return@webSocket
        }

        val userId = try {
            jwtService.verifier().verify(token).getClaim(JWT_CLAIM_USER_ID).asLong()
        } catch (e: JWTVerificationException) {
            null
        }

        if (userId == null || !tournamentRepository.isMember(userId, tournamentId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not authorized for this tournament"))
            return@webSocket
        }

        liveHub.register(tournamentId, this)
        try {
            send(Frame.Text(Json.encodeToString(LiveStateResponse.serializer(), liveStateService.buildCurrentState(tournamentId))))
            for (frame in incoming) {
                // Read-only channel from the client's perspective; incoming frames (e.g. pings) are drained and ignored.
            }
        } finally {
            liveHub.unregister(tournamentId, this)
        }
    }
}
