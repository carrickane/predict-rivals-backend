package com.predictrivals.tournament

import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate
import java.time.OffsetDateTime

data class TournamentRecord(
    val id: Long,
    val name: String,
    val season: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

class TournamentRepository {

    /** MVP assumption: a single tournament is active at a time (see design doc section 1). */
    suspend fun findActiveTournament(): TournamentRecord = dbQuery {
        val today = LocalDate.now()
        TournamentsTable
            .selectAll().where { (TournamentsTable.startDate lessEq today) and (TournamentsTable.endDate greaterEq today) }
            .map { it.toTournamentRecord() }
            .firstOrNull()
            ?: throw ApiException.NotFound("No active tournament")
    }

    suspend fun findById(tournamentId: Long): TournamentRecord = dbQuery {
        TournamentsTable
            .selectAll().where { TournamentsTable.id eq tournamentId }
            .map { it.toTournamentRecord() }
            .singleOrNull()
            ?: throw ApiException.NotFound("Tournament $tournamentId not found")
    }

    suspend fun join(userId: Long, tournamentId: Long): OffsetDateTime = dbQuery {
        val now = OffsetDateTime.now()
        TournamentMembershipsTable.insertIgnore {
            it[TournamentMembershipsTable.userId] = userId
            it[TournamentMembershipsTable.tournamentId] = tournamentId
            it[TournamentMembershipsTable.joinedAt] = now
        }
        TournamentMembershipsTable
            .selectAll().where { (TournamentMembershipsTable.userId eq userId) and (TournamentMembershipsTable.tournamentId eq tournamentId) }
            .map { it[TournamentMembershipsTable.joinedAt] }
            .single()
    }

    suspend fun isMember(userId: Long, tournamentId: Long): Boolean = dbQuery {
        TournamentMembershipsTable
            .selectAll().where { (TournamentMembershipsTable.userId eq userId) and (TournamentMembershipsTable.tournamentId eq tournamentId) }
            .count() > 0
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toTournamentRecord() = TournamentRecord(
        id = this[TournamentsTable.id],
        name = this[TournamentsTable.name],
        season = this[TournamentsTable.season],
        startDate = this[TournamentsTable.startDate],
        endDate = this[TournamentsTable.endDate],
    )
}
