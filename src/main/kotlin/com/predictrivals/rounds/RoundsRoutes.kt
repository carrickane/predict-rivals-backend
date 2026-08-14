package com.predictrivals.rounds

import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.roundsRoutes(roundsRepository: RoundsRepository, tournamentRepository: TournamentRepository) {
    route("/api/rounds") {
        get("/current") {
            val tournament = tournamentRepository.findActiveTournament()
            val round = roundsRepository.findCurrentRound(tournament.id)
            call.respond(RoundResponse(round.id, round.tournamentId, round.roundNumber, round.status))
        }
    }
}
