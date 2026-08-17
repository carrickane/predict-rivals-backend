package com.predictrivals.tournament

import com.predictrivals.auth.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

enum class TournamentFormat {
    solo_points,
    round_robin,
    playoff,
}

enum class TournamentStatus {
    open,
    active,
}

object TournamentsTable : Table("game.tournaments") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 128)
    val ownerUserId = long("owner_user_id").references(UsersTable.id)
    val joinCode = varchar("join_code", 8).uniqueIndex()
    val playerLimit = integer("player_limit")
    val format = varchar("format", 16)
    val status = varchar("status", 16)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object TournamentMembershipsTable : Table("game.tournament_memberships") {
    val userId = long("user_id").references(UsersTable.id)
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val joinedAt = timestampWithTimeZone("joined_at")

    override val primaryKey = PrimaryKey(userId, tournamentId)
}
