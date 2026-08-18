package com.predictrivals.standings

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import com.predictrivals.predictions.PredictionsTable
import com.predictrivals.rounds.RoundsTable
import com.predictrivals.tournament.TournamentMembershipsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

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
}
