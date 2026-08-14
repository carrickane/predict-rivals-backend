package com.predictrivals.standings

import com.predictrivals.common.ApiException
import com.predictrivals.predictions.PredictionsRepository
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.standingsRoutes(
    standingsRepository: StandingsRepository,
    predictionsRepository: PredictionsRepository,
    tournamentRepository: TournamentRepository,
) {
    get("/api/standings") {
        val tournament = tournamentRepository.findActiveTournament()
        val standings = standingsRepository.getStandings(tournament.id)
        call.respond(standings.toRanked())
    }

    get("/api/top-scorers") {
        val tournament = tournamentRepository.findActiveTournament()
        val standings = standingsRepository.getStandings(tournament.id)
        call.respond(
            standings.mapIndexed { index, row ->
                TopScorerEntryResponse(index + 1, row.userId, row.name, row.totalGoals)
            },
        )
    }

    get("/api/users/{id}/stats") {
        val userId = call.parameters["id"]?.toLongOrNull()
            ?: throw ApiException.BadRequest("Invalid user id")
        val tournament = tournamentRepository.findActiveTournament()
        val standing = standingsRepository.getUserStanding(userId, tournament.id)
            ?: throw ApiException.NotFound("User $userId not found")

        val predictions = predictionsRepository.listByUser(userId).filter { !it.isLate && it.pointsAwarded != null }
        val scoredCount = predictions.size
        val correctCount = predictions.count { it.isExact == true || (it.pointsAwarded ?: 0) > 0 }
        val accuracy = if (scoredCount == 0) 0.0 else correctCount.toDouble() / scoredCount

        call.respond(
            UserStatsResponse(
                userId = userId,
                name = standing.name,
                totalGoals = standing.totalGoals,
                totalExactScores = standing.totalExactScores,
                roundsPlayed = standing.roundsPlayed,
                totalPredictions = predictionsRepository.listByUser(userId).size,
                scoredPredictions = scoredCount,
                accuracy = accuracy,
            ),
        )
    }
}
