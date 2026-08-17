package com.predictrivals.adminMatches

import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class AdminMatchRecord(
    val id: Long,
    val tournamentId: Long,
    val externalMatchId: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: OffsetDateTime,
    val roundNumber: Int,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
)

class AdminMatchRepository {

    suspend fun createRoundMatches(tournamentId: Long, roundNumber: Int, matches: List<NewRoundMatch>): List<AdminMatchRecord> = dbQuery {
        val now = OffsetDateTime.now()
        matches.map { match ->
            val id = AdminMatchesTable.insert {
                it[AdminMatchesTable.tournamentId] = tournamentId
                it[externalMatchId] = match.externalMatchId
                it[league] = match.league
                it[homeTeam] = match.homeTeam
                it[awayTeam] = match.awayTeam
                it[kickoffAt] = OffsetDateTime.parse(match.kickoffAt)
                it[AdminMatchesTable.roundNumber] = roundNumber
                it[status] = MatchStatus.scheduled.name
                it[updatedAt] = now
            } get AdminMatchesTable.id

            AdminMatchesTable.selectAll().where { AdminMatchesTable.id eq id }
                .map { it.toRecord() }
                .single()
        }
    }

    suspend fun findById(matchId: Long): AdminMatchRecord? = dbQuery {
        AdminMatchesTable.selectAll().where { AdminMatchesTable.id eq matchId }
            .map { it.toRecord() }
            .singleOrNull()
    }

    suspend fun listByTournamentAndRound(tournamentId: Long, roundNumber: Int): List<AdminMatchRecord> = dbQuery {
        AdminMatchesTable
            .selectAll().where { (AdminMatchesTable.tournamentId eq tournamentId) and (AdminMatchesTable.roundNumber eq roundNumber) }
            .map { it.toRecord() }
    }

    suspend fun listByIds(matchIds: List<Long>): List<AdminMatchRecord> = dbQuery {
        if (matchIds.isEmpty()) return@dbQuery emptyList()
        AdminMatchesTable.selectAll().where { AdminMatchesTable.id inList matchIds }
            .map { it.toRecord() }
    }

    suspend fun listByStatus(status: MatchStatus): List<AdminMatchRecord> = dbQuery {
        AdminMatchesTable.selectAll().where { AdminMatchesTable.status eq status.name }
            .map { it.toRecord() }
    }

    suspend fun listAll(): List<AdminMatchRecord> = dbQuery {
        AdminMatchesTable.selectAll().map { it.toRecord() }
    }

    suspend fun updateScoreAndStatus(matchId: Long, homeScore: Int?, awayScore: Int?, status: MatchStatus) = dbQuery {
        AdminMatchesTable.update({ AdminMatchesTable.id eq matchId }) {
            it[AdminMatchesTable.homeScore] = homeScore
            it[AdminMatchesTable.awayScore] = awayScore
            it[AdminMatchesTable.status] = status.name
            it[updatedAt] = OffsetDateTime.now()
        }
        Unit
    }

    suspend fun getOrThrow(matchId: Long): AdminMatchRecord =
        findById(matchId) ?: throw ApiException.NotFound("Match $matchId not found")

    private fun ResultRow.toRecord() = AdminMatchRecord(
        id = this[AdminMatchesTable.id],
        tournamentId = this[AdminMatchesTable.tournamentId],
        externalMatchId = this[AdminMatchesTable.externalMatchId],
        league = this[AdminMatchesTable.league],
        homeTeam = this[AdminMatchesTable.homeTeam],
        awayTeam = this[AdminMatchesTable.awayTeam],
        kickoffAt = this[AdminMatchesTable.kickoffAt],
        roundNumber = this[AdminMatchesTable.roundNumber],
        status = this[AdminMatchesTable.status],
        homeScore = this[AdminMatchesTable.homeScore],
        awayScore = this[AdminMatchesTable.awayScore],
    )
}
