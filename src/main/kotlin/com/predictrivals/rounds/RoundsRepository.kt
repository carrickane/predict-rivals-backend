package com.predictrivals.rounds

import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

data class RoundRecord(val id: Long, val tournamentId: Long, val roundNumber: Int, val status: String)

class RoundsRepository {

    suspend fun listByTournament(tournamentId: Long): List<RoundRecord> = dbQuery {
        RoundsTable
            .selectAll().where { RoundsTable.tournamentId eq tournamentId }
            .orderBy(RoundsTable.roundNumber to SortOrder.ASC)
            .map { it.toRoundRecord() }
    }

    suspend fun findCurrentRound(tournamentId: Long): RoundRecord = dbQuery {
        val rounds = RoundsTable
            .selectAll().where { RoundsTable.tournamentId eq tournamentId }
            .orderBy(RoundsTable.roundNumber to SortOrder.ASC)
            .map { it.toRoundRecord() }

        rounds.firstOrNull { it.status == RoundStatus.live.name }
            ?: rounds.firstOrNull { it.status == RoundStatus.scheduled.name }
            ?: rounds.lastOrNull { it.status == RoundStatus.finished.name }
            ?: throw ApiException.NotFound("No rounds found for tournament $tournamentId")
    }

    suspend fun findByTournamentAndNumber(tournamentId: Long, roundNumber: Int): RoundRecord? = dbQuery {
        RoundsTable
            .selectAll().where { (RoundsTable.tournamentId eq tournamentId) and (RoundsTable.roundNumber eq roundNumber) }
            .map { it.toRoundRecord() }
            .singleOrNull()
    }

    suspend fun findById(roundId: Long): RoundRecord = dbQuery {
        RoundsTable
            .selectAll().where { RoundsTable.id eq roundId }
            .map { it.toRoundRecord() }
            .singleOrNull()
            ?: throw ApiException.NotFound("Round $roundId not found")
    }

    suspend fun createIfMissing(tournamentId: Long, roundNumber: Int): RoundRecord = dbQuery {
        RoundsTable.insertIgnore {
            it[RoundsTable.tournamentId] = tournamentId
            it[RoundsTable.roundNumber] = roundNumber
            it[status] = RoundStatus.scheduled.name
        }
        RoundsTable
            .selectAll().where { (RoundsTable.tournamentId eq tournamentId) and (RoundsTable.roundNumber eq roundNumber) }
            .map { it.toRoundRecord() }
            .single()
    }

    suspend fun updateStatus(roundId: Long, status: RoundStatus) = dbQuery {
        RoundsTable.update({ RoundsTable.id eq roundId }) {
            it[RoundsTable.status] = status.name
        }
        Unit
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRoundRecord() = RoundRecord(
        id = this[RoundsTable.id],
        tournamentId = this[RoundsTable.tournamentId],
        roundNumber = this[RoundsTable.roundNumber],
        status = this[RoundsTable.status],
    )
}
