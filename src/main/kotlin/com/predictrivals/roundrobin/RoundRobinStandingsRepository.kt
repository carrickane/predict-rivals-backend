package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.scoring.RoundScoresRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class RoundRobinStandingRow(
    val userId: Long,
    val name: String,
    val leaguePoints: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
)

private data class MatchdayOutcome(val points: Int, val wins: Int, val draws: Int, val losses: Int)

private fun outcomeFor(myGoals: Int, opponentGoals: Int): MatchdayOutcome = when {
    myGoals > opponentGoals -> MatchdayOutcome(points = 3, wins = 1, draws = 0, losses = 0)
    myGoals < opponentGoals -> MatchdayOutcome(points = 0, wins = 0, draws = 0, losses = 1)
    else -> MatchdayOutcome(points = 1, wins = 0, draws = 1, losses = 0)
}

class RoundRobinStandingsRepository(
    private val pairingsRepository: TournamentPairingsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val roundsRepository: RoundsRepository,
) {

    /** Bye/BOT matchday: only the player's own goals-for tally moves, nothing else. */
    suspend fun applyByeRound(tournamentId: Long, userId: Long, goalsFor: Int) =
        upsertDelta(tournamentId, userId, pointsDelta = 0, winsDelta = 0, drawsDelta = 0, lossesDelta = 0, goalsForDelta = goalsFor, goalsAgainstDelta = 0)

    suspend fun applyMatchdayResult(tournamentId: Long, playerAUserId: Long, playerBUserId: Long, playerAGoals: Int, playerBGoals: Int) {
        val a = outcomeFor(playerAGoals, playerBGoals)
        val b = outcomeFor(playerBGoals, playerAGoals)
        upsertDelta(tournamentId, playerAUserId, a.points, a.wins, a.draws, a.losses, playerAGoals, playerBGoals)
        upsertDelta(tournamentId, playerBUserId, b.points, b.wins, b.draws, b.losses, playerBGoals, playerAGoals)
    }

    suspend fun getStandings(tournamentId: Long): List<RoundRobinStandingRow> {
        val names = dbQuery { UsersTable.selectAll().associate { it[UsersTable.id] to it[UsersTable.name] } }
        val rows = dbQuery {
            RoundRobinStandingsTable
                .selectAll().where { RoundRobinStandingsTable.tournamentId eq tournamentId }
                .map {
                    RoundRobinStandingRow(
                        userId = it[RoundRobinStandingsTable.userId],
                        name = names[it[RoundRobinStandingsTable.userId]] ?: "Unknown",
                        leaguePoints = it[RoundRobinStandingsTable.leaguePoints],
                        wins = it[RoundRobinStandingsTable.wins],
                        draws = it[RoundRobinStandingsTable.draws],
                        losses = it[RoundRobinStandingsTable.losses],
                        goalsFor = it[RoundRobinStandingsTable.goalsFor],
                        goalsAgainst = it[RoundRobinStandingsTable.goalsAgainst],
                    )
                }
        }
        return rankWithTiebreaks(tournamentId, rows)
    }

    /**
     * Ranks by league points -> goals scored -> wins -> goal difference, then breaks a remaining
     * adjacent 2-way tie by head-to-head result. This does NOT resolve a 3+-way tie via a full
     * mini-league recompute (rare in practice at this scale) — that case, and a head-to-head that's
     * itself still level (e.g. split results across both legs), falls back to the stable userId
     * order already applied by the primary sort, per the design doc.
     */
    private suspend fun rankWithTiebreaks(tournamentId: Long, rows: List<RoundRobinStandingRow>): List<RoundRobinStandingRow> {
        val sorted = rows.sortedWith(
            compareByDescending<RoundRobinStandingRow> { it.leaguePoints }
                .thenByDescending { it.goalsFor }
                .thenByDescending { it.wins }
                .thenByDescending { it.goalsFor - it.goalsAgainst }
                .thenBy { it.userId },
        ).toMutableList()

        var i = 0
        while (i < sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val tied = a.leaguePoints == b.leaguePoints && a.goalsFor == b.goalsFor &&
                a.wins == b.wins && (a.goalsFor - a.goalsAgainst) == (b.goalsFor - b.goalsAgainst)
            if (tied) {
                val winner = headToHeadWinnerUserId(tournamentId, a.userId, b.userId)
                if (winner == b.userId) {
                    sorted[i] = b
                    sorted[i + 1] = a
                }
            }
            i++
        }
        return sorted
    }

    private suspend fun headToHeadWinnerUserId(tournamentId: Long, userIdA: Long, userIdB: Long): Long? {
        val roundNumbers = pairingsRepository.headToHeadRounds(tournamentId, userIdA, userIdB)
        if (roundNumbers.isEmpty()) return null

        var aPoints = 0
        var bPoints = 0
        roundNumbers.forEach { roundNumber ->
            val round = roundsRepository.findByTournamentAndNumber(tournamentId, roundNumber) ?: return@forEach
            val aGoals = roundScoresRepository.find(userIdA, round.id)?.goalsAwarded ?: 0
            val bGoals = roundScoresRepository.find(userIdB, round.id)?.goalsAwarded ?: 0
            when {
                aGoals > bGoals -> aPoints += 3
                aGoals < bGoals -> bPoints += 3
                else -> { aPoints += 1; bPoints += 1 }
            }
        }
        return when {
            aPoints > bPoints -> userIdA
            bPoints > aPoints -> userIdB
            else -> null
        }
    }

    private suspend fun upsertDelta(
        tournamentId: Long,
        userId: Long,
        pointsDelta: Int,
        winsDelta: Int,
        drawsDelta: Int,
        lossesDelta: Int,
        goalsForDelta: Int,
        goalsAgainstDelta: Int,
    ) = dbQuery {
        val now = OffsetDateTime.now()
        val existing = RoundRobinStandingsTable
            .selectAll().where { (RoundRobinStandingsTable.tournamentId eq tournamentId) and (RoundRobinStandingsTable.userId eq userId) }
            .singleOrNull()

        if (existing != null) {
            RoundRobinStandingsTable.update({ (RoundRobinStandingsTable.tournamentId eq tournamentId) and (RoundRobinStandingsTable.userId eq userId) }) {
                it[leaguePoints] = existing[RoundRobinStandingsTable.leaguePoints] + pointsDelta
                it[wins] = existing[RoundRobinStandingsTable.wins] + winsDelta
                it[draws] = existing[RoundRobinStandingsTable.draws] + drawsDelta
                it[losses] = existing[RoundRobinStandingsTable.losses] + lossesDelta
                it[goalsFor] = existing[RoundRobinStandingsTable.goalsFor] + goalsForDelta
                it[goalsAgainst] = existing[RoundRobinStandingsTable.goalsAgainst] + goalsAgainstDelta
                it[updatedAt] = now
            }
        } else {
            RoundRobinStandingsTable.insert {
                it[RoundRobinStandingsTable.tournamentId] = tournamentId
                it[RoundRobinStandingsTable.userId] = userId
                it[leaguePoints] = pointsDelta
                it[wins] = winsDelta
                it[draws] = drawsDelta
                it[losses] = lossesDelta
                it[goalsFor] = goalsForDelta
                it[goalsAgainst] = goalsAgainstDelta
                it[updatedAt] = now
            }
        }
        Unit
    }
}
