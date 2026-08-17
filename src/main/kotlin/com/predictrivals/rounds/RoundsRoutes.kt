package com.predictrivals.rounds

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

fun Route.roundsRoutes(roundsRepository: RoundsRepository, tournamentRepository: TournamentRepository) {
    route("/api/tournaments/{tournamentId}/rounds") {
        authenticate(AUTH_JWT) {
            get("/current") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its rounds")
                }
                val round = roundsRepository.findCurrentRound(tournamentId)
                call.respond(RoundResponse(round.id, round.tournamentId, round.roundNumber, round.status))
            }
        }
    }
}
