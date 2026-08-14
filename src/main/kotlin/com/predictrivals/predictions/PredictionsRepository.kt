package com.predictrivals.predictions

import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class PredictionRecord(
    val id: Long,
    val userId: Long,
    val matchId: Long,
    val roundId: Long,
    val predictedHomeScore: Int,
    val predictedAwayScore: Int,
    val submittedAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val isLate: Boolean,
    val pointsAwarded: Int?,
    val isExact: Boolean?,
)

class PredictionsRepository {

    suspend fun findByUserAndMatch(userId: Long, matchId: Long): PredictionRecord? = dbQuery {
        PredictionsTable.selectAll().where { (PredictionsTable.userId eq userId) and (PredictionsTable.matchId eq matchId) }
            .map { it.toRecord() }
            .singleOrNull()
    }

    suspend fun upsert(
        userId: Long,
        matchId: Long,
        roundId: Long,
        homeScore: Int,
        awayScore: Int,
        isLate: Boolean,
    ): PredictionRecord = dbQuery {
        val now = OffsetDateTime.now()
        val existingId = PredictionsTable
            .selectAll().where { (PredictionsTable.userId eq userId) and (PredictionsTable.matchId eq matchId) }
            .map { it[PredictionsTable.id] }
            .singleOrNull()

        val id = if (existingId != null) {
            PredictionsTable.update({ PredictionsTable.id eq existingId }) {
                it[predictedHomeScore] = homeScore
                it[predictedAwayScore] = awayScore
                it[updatedAt] = now
                it[PredictionsTable.isLate] = isLate
            }
            existingId
        } else {
            PredictionsTable.insert {
                it[PredictionsTable.userId] = userId
                it[PredictionsTable.matchId] = matchId
                it[PredictionsTable.roundId] = roundId
                it[predictedHomeScore] = homeScore
                it[predictedAwayScore] = awayScore
                it[submittedAt] = now
                it[updatedAt] = now
                it[PredictionsTable.isLate] = isLate
            } get PredictionsTable.id
        }

        PredictionsTable.selectAll().where { PredictionsTable.id eq id }.map { it.toRecord() }.single()
    }

    suspend fun listByMatch(matchId: Long): List<PredictionRecord> = dbQuery {
        PredictionsTable.selectAll().where { PredictionsTable.matchId eq matchId }.map { it.toRecord() }
    }

    suspend fun listByRoundAndUser(roundId: Long, userId: Long): List<PredictionRecord> = dbQuery {
        PredictionsTable
            .selectAll().where { (PredictionsTable.roundId eq roundId) and (PredictionsTable.userId eq userId) }
            .map { it.toRecord() }
    }

    suspend fun listByRound(roundId: Long): List<PredictionRecord> = dbQuery {
        PredictionsTable.selectAll().where { PredictionsTable.roundId eq roundId }.map { it.toRecord() }
    }

    suspend fun listByUser(userId: Long): List<PredictionRecord> = dbQuery {
        PredictionsTable.selectAll().where { PredictionsTable.userId eq userId }.map { it.toRecord() }
    }

    suspend fun updateScore(predictionId: Long, points: Int, isExact: Boolean) = dbQuery {
        PredictionsTable.update({ PredictionsTable.id eq predictionId }) {
            it[pointsAwarded] = points
            it[PredictionsTable.isExact] = isExact
        }
        Unit
    }

    private fun ResultRow.toRecord() = PredictionRecord(
        id = this[PredictionsTable.id],
        userId = this[PredictionsTable.userId],
        matchId = this[PredictionsTable.matchId],
        roundId = this[PredictionsTable.roundId],
        predictedHomeScore = this[PredictionsTable.predictedHomeScore],
        predictedAwayScore = this[PredictionsTable.predictedAwayScore],
        submittedAt = this[PredictionsTable.submittedAt],
        updatedAt = this[PredictionsTable.updatedAt],
        isLate = this[PredictionsTable.isLate],
        pointsAwarded = this[PredictionsTable.pointsAwarded],
        isExact = this[PredictionsTable.isExact],
    )
}
