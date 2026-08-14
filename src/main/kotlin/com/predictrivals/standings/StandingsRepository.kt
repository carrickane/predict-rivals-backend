package com.predictrivals.standings

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.scoring.RoundScoresTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class StandingRow(
    val userId: Long,
    val name: String,
    val totalGoals: Int,
    val totalExactScores: Int,
    val roundsPlayed: Int,
)

class StandingsRepository {

    /** Called once, idempotently, when a round is frozen (see ScoringService.maybeFreezeRound). */
    suspend fun applyFinishedRound(tournamentId: Long, roundId: Long) = dbQuery {
        val now = OffsetDateTime.now()
        RoundScoresTable.selectAll().where { RoundScoresTable.roundId eq roundId }.forEach { row ->
            val userId = row[RoundScoresTable.userId]
            val goals = row[RoundScoresTable.goalsAwarded]
            val exactCount = row[RoundScoresTable.exactCount]

            val existing = StandingsTable
                .selectAll().where { (StandingsTable.userId eq userId) and (StandingsTable.tournamentId eq tournamentId) }
                .singleOrNull()

            if (existing != null) {
                StandingsTable.update({ (StandingsTable.userId eq userId) and (StandingsTable.tournamentId eq tournamentId) }) {
                    it[totalGoals] = existing[StandingsTable.totalGoals] + goals
                    it[totalExactScores] = existing[StandingsTable.totalExactScores] + exactCount
                    it[roundsPlayed] = existing[StandingsTable.roundsPlayed] + 1
                    it[updatedAt] = now
                }
            } else {
                StandingsTable.insert {
                    it[StandingsTable.userId] = userId
                    it[StandingsTable.tournamentId] = tournamentId
                    it[totalGoals] = goals
                    it[totalExactScores] = exactCount
                    it[roundsPlayed] = 1
                    it[updatedAt] = now
                }
            }
        }
        Unit
    }

    /**
     * Frozen standings plus, if [liveRoundId] is given, the current round's provisional
     * (not-yet-frozen) goals layered on top — this is what feeds both GET /api/standings
     * (liveRoundId = null) and the live screen's table (liveRoundId = current round).
     */
    suspend fun getStandings(tournamentId: Long, liveRoundId: Long? = null): List<StandingRow> = dbQuery {
        val names = UsersTable.selectAll().associate { it[UsersTable.id] to it[UsersTable.name] }

        val totals = StandingsTable
            .selectAll().where { StandingsTable.tournamentId eq tournamentId }
            .associate { row ->
                row[StandingsTable.userId] to Triple(
                    row[StandingsTable.totalGoals],
                    row[StandingsTable.totalExactScores],
                    row[StandingsTable.roundsPlayed],
                )
            }
            .toMutableMap()

        if (liveRoundId != null) {
            RoundScoresTable
                .selectAll().where { (RoundScoresTable.roundId eq liveRoundId) and (RoundScoresTable.isFrozen eq false) }
                .forEach { row ->
                    val userId = row[RoundScoresTable.userId]
                    val current = totals[userId] ?: Triple(0, 0, 0)
                    totals[userId] = Triple(
                        current.first + row[RoundScoresTable.goalsAwarded],
                        current.second + row[RoundScoresTable.exactCount],
                        current.third,
                    )
                }
        }

        totals.entries
            .map { (userId, totals) ->
                StandingRow(userId, names[userId] ?: "Unknown", totals.first, totals.second, totals.third)
            }
            .sortedWith(compareByDescending<StandingRow> { it.totalGoals }.thenByDescending { it.totalExactScores })
    }

    suspend fun getUserStanding(userId: Long, tournamentId: Long): StandingRow? = dbQuery {
        val name = UsersTable.selectAll().where { UsersTable.id eq userId }.map { it[UsersTable.name] }.singleOrNull() ?: return@dbQuery null
        StandingsTable
            .selectAll().where { (StandingsTable.userId eq userId) and (StandingsTable.tournamentId eq tournamentId) }
            .map { StandingRow(userId, name, it[StandingsTable.totalGoals], it[StandingsTable.totalExactScores], it[StandingsTable.roundsPlayed]) }
            .singleOrNull()
            ?: StandingRow(userId, name, 0, 0, 0)
    }
}
