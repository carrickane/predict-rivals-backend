package com.predictrivals.live

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.scoring.RoundScoresRepository
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.tournament.TournamentRepository
import org.jetbrains.exposed.sql.selectAll

/** Assembles the combined live payload (matches + standings + round-in-progress scores) shared by GET /api/live and every WebSocket broadcast. */
class LiveStateService(
    private val tournamentRepository: TournamentRepository,
    private val roundsRepository: RoundsRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val standingsRepository: StandingsRepository,
    private val roundScoresRepository: RoundScoresRepository,
) {
    suspend fun buildCurrentState(): LiveStateResponse {
        val tournament = tournamentRepository.findActiveTournament()
        val round = roundsRepository.findCurrentRound(tournament.id)
        val matches = adminMatchRepository.listByRoundNumber(round.roundNumber)

        val names = dbQuery { UsersTable.selectAll().associate { it[UsersTable.id] to it[UsersTable.name] } }

        val standings = standingsRepository.getStandings(tournament.id, liveRoundId = round.id)
            .mapIndexed { index, row ->
                LiveStandingEntry(index + 1, row.userId, row.name, row.totalGoals, row.totalExactScores)
            }

        val roundScores = roundScoresRepository.listByRound(round.id)
            .filter { !it.isFrozen }
            .map { LiveRoundScoreEntry(it.userId, names[it.userId] ?: "Unknown", it.pointsRaw, it.goalsAwarded) }

        return LiveStateResponse(
            matches = matches.map {
                LiveMatchResponse(it.id, it.homeTeam, it.awayTeam, it.kickoffAt.toString(), it.status, it.homeScore, it.awayScore)
            },
            standings = standings,
            roundScores = roundScores,
        )
    }
}
