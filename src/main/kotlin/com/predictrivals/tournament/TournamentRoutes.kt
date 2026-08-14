package com.predictrivals.tournament

import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.tournamentRoutes(tournamentRepository: TournamentRepository) {
    route("/api/tournament") {
        authenticate(AUTH_JWT) {
            post("/join") {
                val userId = call.principalUserId()
                val tournament = tournamentRepository.findActiveTournament()
                val joinedAt = tournamentRepository.join(userId, tournament.id)
                call.respond(JoinTournamentResponse(tournament.id, joinedAt.toString()))
            }
        }
    }
}
