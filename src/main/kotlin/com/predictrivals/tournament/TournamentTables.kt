package com.predictrivals.tournament

import com.predictrivals.auth.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object TournamentsTable : Table("game.tournaments") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 128)
    val season = varchar("season", 32)
    val startDate = date("start_date")
    val endDate = date("end_date")

    override val primaryKey = PrimaryKey(id)
}

object TournamentMembershipsTable : Table("game.tournament_memberships") {
    val userId = long("user_id").references(UsersTable.id)
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val joinedAt = timestampWithTimeZone("joined_at")

    override val primaryKey = PrimaryKey(userId, tournamentId)
}
