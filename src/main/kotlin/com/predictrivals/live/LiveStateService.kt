package com.predictrivals.live

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.standings.StandingsRepository

/** Assembles the combined live payload (matches + standings + round-in-progress scores) shared by GET .../live and every WebSocket broadcast. */
class LiveStateService(
    private val roundsRepository: RoundsRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val standingsRepository: StandingsRepository,
) {
    suspend fun buildCurrentState(tournamentId: Long): LiveStateResponse {
        val round = roundsRepository.findCurrentRound(tournamentId)
        val matches = adminMatchRepository.listByTournamentAndRound(tournamentId, round.roundNumber)

        val standings = standingsRepository.getSoloStandings(tournamentId)
            .mapIndexed { index, row -> LiveStandingEntry(index + 1, row.userId, row.name, row.totalPoints, row.exactCount) }

        val roundScores = standingsRepository.getSoloRoundScores(tournamentId, round.id)
            .map { LiveRoundScoreEntry(it.userId, it.name, it.totalPoints) }

        return LiveStateResponse(
            matches = matches.map {
                LiveMatchResponse(it.id, it.homeTeam, it.awayTeam, it.kickoffAt.toString(), it.status, it.homeScore, it.awayScore)
            },
            standings = standings,
            roundScores = roundScores,
        )
    }
}
