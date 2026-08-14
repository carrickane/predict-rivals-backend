package com.predictrivals.rounds

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.AdminMatchResponse
import com.predictrivals.adminMatches.toResponse
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class CalendarRoundResponse(val round: RoundResponse, val matches: List<AdminMatchResponse>)

/** Derived query joining rounds and admin_matches (by round_number) — deliberately not a stored table, see design doc section 3. */
fun Route.calendarRoutes(
    roundsRepository: RoundsRepository,
    adminMatchRepository: AdminMatchRepository,
    tournamentRepository: TournamentRepository,
) {
    get("/api/calendar") {
        val tournament = tournamentRepository.findActiveTournament()
        val rounds = roundsRepository.listByTournament(tournament.id)
        val calendar = rounds.map { round ->
            val matches = adminMatchRepository.listByRoundNumber(round.roundNumber)
            CalendarRoundResponse(
                RoundResponse(round.id, round.tournamentId, round.roundNumber, round.status),
                matches.map { it.toResponse() },
            )
        }
        call.respond(calendar)
    }
}
