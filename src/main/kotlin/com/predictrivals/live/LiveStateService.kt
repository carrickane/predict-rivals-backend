package com.predictrivals.live

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.scoring.RoundScoresRepository
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentMembershipsTable
import com.predictrivals.tournament.TournamentRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

/** Assembles the combined live payload (matches + standings + round-in-progress scores) shared by GET .../live and every WebSocket broadcast. */
class LiveStateService(
    private val roundsRepository: RoundsRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val standingsRepository: StandingsRepository,
    private val roundRobinStandingsRepository: RoundRobinStandingsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val tournamentRepository: TournamentRepository,
) {
    suspend fun buildCurrentState(tournamentId: Long): LiveStateResponse {
        val round = roundsRepository.findCurrentRound(tournamentId)
        val matches = adminMatchRepository.listByTournamentAndRound(tournamentId, round.roundNumber)
        val tournament = tournamentRepository.findById(tournamentId)

        val matchResponses = matches.map {
            LiveMatchResponse(it.id, it.homeTeam, it.awayTeam, it.kickoffAt.toString(), it.status, it.homeScore, it.awayScore)
        }

        if (tournament.format == TournamentFormat.round_robin.name) {
            val standings = roundRobinStandingsRepository.getStandings(tournamentId)
                .mapIndexed { index, row ->
                    LiveStandingEntry(
                        rank = index + 1,
                        userId = row.userId,
                        name = row.name,
                        leaguePoints = row.leaguePoints,
                        wins = row.wins,
                        draws = row.draws,
                        losses = row.losses,
                        goalsFor = row.goalsFor,
                        goalsAgainst = row.goalsAgainst,
                    )
                }
            val roundScores = liveRoundGoals(tournamentId, round.id)
            return LiveStateResponse(matchResponses, standings, roundScores)
        }

        val standings = standingsRepository.getSoloStandings(tournamentId)
            .mapIndexed { index, row -> LiveStandingEntry(index + 1, row.userId, row.name, totalPoints = row.totalPoints, exactCount = row.exactCount) }
        val roundScores = standingsRepository.getSoloRoundScores(tournamentId, round.id)
            .map { LiveRoundScoreEntry(it.userId, it.name, it.totalPoints) }

        return LiveStateResponse(matchResponses, standings, roundScores)
    }

    /** In-progress (not-yet-frozen included) per-user round-goals for round_robin's live round-score breakdown. */
    private suspend fun liveRoundGoals(tournamentId: Long, roundId: Long): List<LiveRoundScoreEntry> {
        val names = dbQuery {
            (TournamentMembershipsTable innerJoin UsersTable)
                .selectAll().where { TournamentMembershipsTable.tournamentId eq tournamentId }
                .associate { it[UsersTable.id] to it[UsersTable.name] }
        }
        return roundScoresRepository.listByRound(roundId).map { record ->
            LiveRoundScoreEntry(record.userId, names[record.userId] ?: "Unknown", record.goalsAwarded)
        }
    }
}
