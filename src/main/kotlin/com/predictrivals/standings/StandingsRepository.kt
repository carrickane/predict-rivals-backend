package com.predictrivals.standings

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import com.predictrivals.predictions.PredictionsTable
import com.predictrivals.rounds.RoundsTable
import com.predictrivals.scoring.RoundScoresTable
import com.predictrivals.tournament.TournamentMembershipsTable
import org.jetbrains.exposed.sql.ResultRow
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

data class SoloStandingRow(
    val userId: Long,
    val name: String,
    val totalPoints: Int,
    val exactCount: Int,
)

data class UserTournamentStats(
    val userId: Long,
    val name: String,
    val totalPoints: Int,
    val exactCount: Int,
    val totalPredictions: Int,
    val scoredPredictions: Int,
    val accuracy: Double,
)

class StandingsRepository {

    /**
     * Live leaderboard for `solo_points` tournaments — no round freezing or goal conversion
     * involved (see design doc section 3). An exact score counts as 3 points directly.
     * Recomputed fresh on every call rather than stored, since a live match can rescore a
     * prediction repeatedly; correctness beats caching at this scale.
     */
    suspend fun getSoloStandings(tournamentId: Long): List<SoloStandingRow> = dbQuery {
        val memberNames = fetchMemberNames(tournamentId)
        val rows = (PredictionsTable innerJoin RoundsTable)
            .selectAll().where { RoundsTable.tournamentId eq tournamentId }
            .filter { !it[PredictionsTable.isLate] && it[PredictionsTable.pointsAwarded] != null }
        aggregateSoloPoints(memberNames, rows)
    }

    /** Same aggregation as [getSoloStandings], scoped to a single round — used for the live screen's round-in-progress breakdown. */
    suspend fun getSoloRoundScores(tournamentId: Long, roundId: Long): List<SoloStandingRow> = dbQuery {
        val memberNames = fetchMemberNames(tournamentId)
        val rows = PredictionsTable.selectAll().where { PredictionsTable.roundId eq roundId }
            .filter { !it[PredictionsTable.isLate] && it[PredictionsTable.pointsAwarded] != null }
        aggregateSoloPoints(memberNames, rows)
    }

    private fun fetchMemberNames(tournamentId: Long): Map<Long, String> =
        (TournamentMembershipsTable innerJoin UsersTable)
            .selectAll().where { TournamentMembershipsTable.tournamentId eq tournamentId }
            .associate { it[UsersTable.id] to it[UsersTable.name] }

    private fun aggregateSoloPoints(memberNames: Map<Long, String>, rows: Iterable<ResultRow>): List<SoloStandingRow> {
        val totals = memberNames.keys.associateWith { 0 to 0 }.toMutableMap()
        rows.forEach { row ->
            val userId = row[PredictionsTable.userId]
            val isExact = row[PredictionsTable.isExact] == true
            val points = row[PredictionsTable.pointsAwarded]!! + (if (isExact) 3 else 0)
            val (curPoints, curExact) = totals[userId] ?: (0 to 0)
            totals[userId] = (curPoints + points) to (curExact + if (isExact) 1 else 0)
        }
        return totals.entries
            .map { (userId, total) -> SoloStandingRow(userId, memberNames[userId] ?: "Unknown", total.first, total.second) }
            .sortedWith(compareByDescending<SoloStandingRow> { it.totalPoints }.thenByDescending { it.exactCount })
    }

    suspend fun getUserSoloStats(tournamentId: Long, userId: Long): UserTournamentStats = dbQuery {
        val name = (TournamentMembershipsTable innerJoin UsersTable)
            .selectAll().where { (TournamentMembershipsTable.tournamentId eq tournamentId) and (TournamentMembershipsTable.userId eq userId) }
            .map { it[UsersTable.name] }
            .singleOrNull()
            ?: throw ApiException.NotFound("User $userId is not a member of tournament $tournamentId")

        val allPredictions = (PredictionsTable innerJoin RoundsTable)
            .selectAll().where { (RoundsTable.tournamentId eq tournamentId) and (PredictionsTable.userId eq userId) }
            .toList()

        val scored = allPredictions.filter { !it[PredictionsTable.isLate] && it[PredictionsTable.pointsAwarded] != null }
        val exactCount = scored.count { it[PredictionsTable.isExact] == true }
        val totalPoints = scored.sumOf { it[PredictionsTable.pointsAwarded]!! } + exactCount * 3
        val correctCount = scored.count { it[PredictionsTable.isExact] == true || (it[PredictionsTable.pointsAwarded] ?: 0) > 0 }
        val accuracy = if (scored.isEmpty()) 0.0 else correctCount.toDouble() / scored.size

        UserTournamentStats(userId, name, totalPoints, exactCount, allPredictions.size, scored.size, accuracy)
    }

    /** Reserved for round_robin/playoff (phase 2) — unused while every tournament is solo_points.
     *  Called once, idempotently, when a round is frozen (see ScoringService.maybeFreezeRound). */
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
