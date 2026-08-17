package com.predictrivals.rounds

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.AdminMatchResponse
import com.predictrivals.adminMatches.toResponse
import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class CalendarRoundResponse(val round: RoundResponse, val matches: List<AdminMatchResponse>)

/** Derived query joining rounds and admin_matches (by tournament_id + round_number) — deliberately not a stored table, see design doc section 3. */
fun Route.calendarRoutes(
    roundsRepository: RoundsRepository,
    adminMatchRepository: AdminMatchRepository,
    tournamentRepository: TournamentRepository,
) {
    authenticate(AUTH_JWT) {
        get("/api/tournaments/{tournamentId}/calendar") {
            val userId = call.principalUserId()
            val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                ?: throw ApiException.BadRequest("Invalid tournament id")
            if (!tournamentRepository.isMember(userId, tournamentId)) {
                throw ApiException.Forbidden("Join the tournament to view its calendar")
            }

            val rounds = roundsRepository.listByTournament(tournamentId)
            val calendar = rounds.map { round ->
                val matches = adminMatchRepository.listByTournamentAndRound(tournamentId, round.roundNumber)
                CalendarRoundResponse(
                    RoundResponse(round.id, round.tournamentId, round.roundNumber, round.status),
                    matches.map { it.toResponse() },
                )
            }
            call.respond(calendar)
        }
    }
}
