package com.predictrivals.standings

import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.standingsRoutes(
    standingsRepository: StandingsRepository,
    tournamentRepository: TournamentRepository,
) {
    route("/api/tournaments/{tournamentId}") {
        authenticate(AUTH_JWT) {
            get("/standings") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its standings")
                }
                call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
            }

            get("/top-scorers") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its top scorers")
                }
                // solo_points has no separate "goals" unit - top scorers is the same ranking as standings
                call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
            }

            get("/users/{userId}/stats") {
                val callerId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(callerId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its stats")
                }
                val targetUserId = call.parameters["userId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid user id")
                call.respond(standingsRepository.getUserSoloStats(tournamentId, targetUserId).toResponse())
            }
        }
    }
}
