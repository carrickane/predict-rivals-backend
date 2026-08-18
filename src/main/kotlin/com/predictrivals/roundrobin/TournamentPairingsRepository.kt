package com.predictrivals.roundrobin

import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

data class PairingRecord(val tournamentId: Long, val roundNumber: Int, val playerAUserId: Long, val playerBUserId: Long?)

class TournamentPairingsRepository {

    suspend fun hasSchedule(tournamentId: Long): Boolean = dbQuery {
        TournamentPairingsTable.selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }.limit(1).any()
    }

    /** Idempotent: no-op if a schedule already exists for this tournament. */
    suspend fun ensureScheduleGenerated(tournamentId: Long, memberUserIds: List<Long>) {
        if (hasSchedule(tournamentId)) return
        val matchdays = RoundRobinScheduler.generate(memberUserIds)
        dbQuery {
            matchdays.forEachIndexed { index, pairs ->
                val roundNumber = index + 1
                pairs.forEach { (a, b) ->
                    TournamentPairingsTable.insert {
                        it[TournamentPairingsTable.tournamentId] = tournamentId
                        it[TournamentPairingsTable.roundNumber] = roundNumber
                        it[playerAUserId] = a
                        it[playerBUserId] = b
                    }
                    if (b != null) {
                        TournamentPairingsTable.insert {
                            it[TournamentPairingsTable.tournamentId] = tournamentId
                            it[TournamentPairingsTable.roundNumber] = roundNumber
                            it[playerAUserId] = b
                            it[playerBUserId] = a
                        }
                    }
                }
            }
        }
    }

    suspend fun listForRound(tournamentId: Long, roundNumber: Int): List<PairingRecord> = dbQuery {
        TournamentPairingsTable
            .selectAll().where { (TournamentPairingsTable.tournamentId eq tournamentId) and (TournamentPairingsTable.roundNumber eq roundNumber) }
            .map { it.toRecord() }
    }

    suspend fun listAllForTournament(tournamentId: Long): List<PairingRecord> = dbQuery {
        TournamentPairingsTable
            .selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }
            .orderBy(TournamentPairingsTable.roundNumber to SortOrder.ASC)
            .map { it.toRecord() }
    }

    suspend fun maxRoundNumber(tournamentId: Long): Int? = dbQuery {
        TournamentPairingsTable
            .selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }
            .maxOfOrNull { it[TournamentPairingsTable.roundNumber] }
    }

    /** Head-to-head lookup for standings tie-breaking: which round_numbers this pair played. */
    suspend fun headToHeadRounds(tournamentId: Long, userIdA: Long, userIdB: Long): List<Int> = dbQuery {
        TournamentPairingsTable
            .selectAll().where {
                (TournamentPairingsTable.tournamentId eq tournamentId) and
                    (TournamentPairingsTable.playerAUserId eq userIdA) and
                    (TournamentPairingsTable.playerBUserId eq userIdB)
            }
            .map { it[TournamentPairingsTable.roundNumber] }
    }

    private fun ResultRow.toRecord() = PairingRecord(
        tournamentId = this[TournamentPairingsTable.tournamentId],
        roundNumber = this[TournamentPairingsTable.roundNumber],
        playerAUserId = this[TournamentPairingsTable.playerAUserId],
        playerBUserId = this[TournamentPairingsTable.playerBUserId],
    )
}
