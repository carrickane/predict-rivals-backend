package com.predictrivals.standings

import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.standingsRoutes(
    standingsRepository: StandingsRepository,
    roundRobinStandingsRepository: RoundRobinStandingsRepository,
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
                val tournament = tournamentRepository.findById(tournamentId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    call.respond(roundRobinStandingsRepository.getStandings(tournamentId).toRankedRoundRobin())
                } else {
                    call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
                }
            }

            get("/top-scorers") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its top scorers")
                }
                val tournament = tournamentRepository.findById(tournamentId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    call.respond(roundRobinStandingsRepository.getStandings(tournamentId).toTopScorersRoundRobin())
                } else {
                    // solo_points has no separate "goals" unit - top scorers is the same ranking as standings
                    call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
                }
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
                // NOTE: per-user stats stay solo-shaped for both formats in this iteration — a
                // round_robin-specific stats shape (W/D/L for this one player) wasn't designed;
                // out of scope here, tracked as a known gap.
                call.respond(standingsRepository.getUserSoloStats(tournamentId, targetUserId).toResponse())
            }
        }
    }
}
