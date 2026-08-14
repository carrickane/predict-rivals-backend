package com.predictrivals.scoring

import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class RoundScoreRecord(
    val userId: Long,
    val roundId: Long,
    val pointsRaw: Int,
    val exactCount: Int,
    val goalsAwarded: Int,
    val isFrozen: Boolean,
)

class RoundScoresRepository {

    suspend fun find(userId: Long, roundId: Long): RoundScoreRecord? = dbQuery {
        RoundScoresTable.selectAll().where { (RoundScoresTable.userId eq userId) and (RoundScoresTable.roundId eq roundId) }
            .map { it.toRecord() }
            .singleOrNull()
    }

    suspend fun listByRound(roundId: Long): List<RoundScoreRecord> = dbQuery {
        RoundScoresTable.selectAll().where { RoundScoresTable.roundId eq roundId }.map { it.toRecord() }
    }

    suspend fun upsert(userId: Long, roundId: Long, pointsRaw: Int, exactCount: Int, goalsAwarded: Int): RoundScoreRecord = dbQuery {
        val now = OffsetDateTime.now()
        val existing = RoundScoresTable
            .selectAll().where { (RoundScoresTable.userId eq userId) and (RoundScoresTable.roundId eq roundId) }
            .singleOrNull()

        if (existing != null) {
            if (existing[RoundScoresTable.isFrozen]) return@dbQuery existing.toRecord()
            RoundScoresTable.update({ (RoundScoresTable.userId eq userId) and (RoundScoresTable.roundId eq roundId) }) {
                it[RoundScoresTable.pointsRaw] = pointsRaw
                it[RoundScoresTable.exactCount] = exactCount
                it[RoundScoresTable.goalsAwarded] = goalsAwarded
                it[computedAt] = now
            }
        } else {
            RoundScoresTable.insert {
                it[RoundScoresTable.userId] = userId
                it[RoundScoresTable.roundId] = roundId
                it[RoundScoresTable.pointsRaw] = pointsRaw
                it[RoundScoresTable.exactCount] = exactCount
                it[RoundScoresTable.goalsAwarded] = goalsAwarded
                it[isFrozen] = false
                it[computedAt] = now
            }
        }

        RoundScoresTable
            .selectAll().where { (RoundScoresTable.userId eq userId) and (RoundScoresTable.roundId eq roundId) }
            .map { it.toRecord() }
            .single()
    }

    suspend fun freeze(roundId: Long) = dbQuery {
        RoundScoresTable.update({ RoundScoresTable.roundId eq roundId }) {
            it[isFrozen] = true
        }
        Unit
    }

    private fun ResultRow.toRecord() = RoundScoreRecord(
        userId = this[RoundScoresTable.userId],
        roundId = this[RoundScoresTable.roundId],
        pointsRaw = this[RoundScoresTable.pointsRaw],
        exactCount = this[RoundScoresTable.exactCount],
        goalsAwarded = this[RoundScoresTable.goalsAwarded],
        isFrozen = this[RoundScoresTable.isFrozen],
    )
}
