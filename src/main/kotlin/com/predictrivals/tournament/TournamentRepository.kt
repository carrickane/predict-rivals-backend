package com.predictrivals.tournament

import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.OffsetDateTime

data class TournamentRecord(
    val id: Long,
    val name: String,
    val ownerUserId: Long,
    val joinCode: String,
    val playerLimit: Int,
    val format: String,
    val status: String,
    val createdAt: OffsetDateTime,
)

/** 6 uppercase alphanumeric chars, excluding easily-confused 0/O and 1/I. */
private const val JOIN_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val JOIN_CODE_LENGTH = 6
private const val MAX_JOIN_CODE_ATTEMPTS = 10

class TournamentRepository {
    private val secureRandom = SecureRandom()

    suspend fun create(name: String, ownerUserId: Long, playerLimit: Int): TournamentRecord = dbQuery {
        val now = OffsetDateTime.now()
        val joinCode = generateUniqueJoinCodeBlocking()

        val tournamentId = TournamentsTable.insert {
            it[TournamentsTable.name] = name
            it[TournamentsTable.ownerUserId] = ownerUserId
            it[TournamentsTable.joinCode] = joinCode
            it[TournamentsTable.playerLimit] = playerLimit
            it[format] = TournamentFormat.solo_points.name
            it[status] = TournamentStatus.open.name
            it[createdAt] = now
        } get TournamentsTable.id

        TournamentMembershipsTable.insert {
            it[userId] = ownerUserId
            it[TournamentMembershipsTable.tournamentId] = tournamentId
            it[joinedAt] = now
        }

        TournamentsTable.selectAll().where { TournamentsTable.id eq tournamentId }
            .map { it.toRecord() }
            .single()
    }

    suspend fun findById(tournamentId: Long): TournamentRecord = dbQuery {
        TournamentsTable.selectAll().where { TournamentsTable.id eq tournamentId }
            .map { it.toRecord() }
            .singleOrNull()
            ?: throw ApiException.NotFound("Tournament $tournamentId not found")
    }

    suspend fun findByJoinCode(joinCode: String): TournamentRecord? = dbQuery {
        TournamentsTable.selectAll().where { TournamentsTable.joinCode eq joinCode }
            .map { it.toRecord() }
            .singleOrNull()
    }

    /** Tournaments the user owns or has joined — owners always have a membership row too. */
    suspend fun listForUser(userId: Long): List<TournamentRecord> = dbQuery {
        (TournamentsTable innerJoin TournamentMembershipsTable)
            .selectAll().where { TournamentMembershipsTable.userId eq userId }
            .orderBy(TournamentsTable.createdAt to SortOrder.DESC)
            .map { it.toRecord() }
    }

    suspend fun memberCount(tournamentId: Long): Long = dbQuery {
        TournamentMembershipsTable.selectAll().where { TournamentMembershipsTable.tournamentId eq tournamentId }.count()
    }

    suspend fun isMember(userId: Long, tournamentId: Long): Boolean = dbQuery {
        TournamentMembershipsTable
            .selectAll().where { (TournamentMembershipsTable.userId eq userId) and (TournamentMembershipsTable.tournamentId eq tournamentId) }
            .count() > 0
    }

    /** Joins the tournament; auto-starts it if this join fills the player limit. */
    suspend fun join(userId: Long, joinCode: String): TournamentRecord = dbQuery {
        val tournament = TournamentsTable.selectAll().where { TournamentsTable.joinCode eq joinCode }
            .map { it.toRecord() }
            .singleOrNull()
            ?: throw ApiException.NotFound("No tournament found for that join code")

        if (tournament.status != TournamentStatus.open.name) {
            throw ApiException.Conflict("Tournament has already started")
        }

        val alreadyMember = TournamentMembershipsTable
            .selectAll().where { (TournamentMembershipsTable.userId eq userId) and (TournamentMembershipsTable.tournamentId eq tournament.id) }
            .count() > 0
        if (alreadyMember) return@dbQuery tournament

        val currentCount = TournamentMembershipsTable
            .selectAll().where { TournamentMembershipsTable.tournamentId eq tournament.id }
            .count()
        if (currentCount >= tournament.playerLimit) {
            throw ApiException.Conflict("Tournament is full")
        }

        TournamentMembershipsTable.insert {
            it[TournamentMembershipsTable.userId] = userId
            it[TournamentMembershipsTable.tournamentId] = tournament.id
            it[joinedAt] = OffsetDateTime.now()
        }

        val newCount = currentCount + 1
        if (newCount >= tournament.playerLimit) {
            TournamentsTable.update({ TournamentsTable.id eq tournament.id }) {
                it[status] = TournamentStatus.active.name
            }
        }

        TournamentsTable.selectAll().where { TournamentsTable.id eq tournament.id }
            .map { it.toRecord() }
            .single()
    }

    suspend fun startNow(tournamentId: Long, callerUserId: Long): TournamentRecord = dbQuery {
        val tournament = TournamentsTable.selectAll().where { TournamentsTable.id eq tournamentId }
            .map { it.toRecord() }
            .singleOrNull()
            ?: throw ApiException.NotFound("Tournament $tournamentId not found")

        if (tournament.ownerUserId != callerUserId) {
            throw ApiException.Forbidden("Only the tournament owner can start it")
        }
        if (tournament.status != TournamentStatus.open.name) {
            throw ApiException.Conflict("Tournament has already started")
        }

        TournamentsTable.update({ TournamentsTable.id eq tournamentId }) {
            it[status] = TournamentStatus.active.name
        }

        TournamentsTable.selectAll().where { TournamentsTable.id eq tournamentId }
            .map { it.toRecord() }
            .single()
    }

    private fun generateUniqueJoinCodeBlocking(): String {
        repeat(MAX_JOIN_CODE_ATTEMPTS) {
            val candidate = (1..JOIN_CODE_LENGTH)
                .map { JOIN_CODE_CHARS[secureRandom.nextInt(JOIN_CODE_CHARS.length)] }
                .joinToString("")
            val exists = TournamentsTable.selectAll().where { TournamentsTable.joinCode eq candidate }.count() > 0
            if (!exists) return candidate
        }
        error("Failed to generate a unique tournament join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
    }

    private fun ResultRow.toRecord() = TournamentRecord(
        id = this[TournamentsTable.id],
        name = this[TournamentsTable.name],
        ownerUserId = this[TournamentsTable.ownerUserId],
        joinCode = this[TournamentsTable.joinCode],
        playerLimit = this[TournamentsTable.playerLimit],
        format = this[TournamentsTable.format],
        status = this[TournamentsTable.status],
        createdAt = this[TournamentsTable.createdAt],
    )
}
