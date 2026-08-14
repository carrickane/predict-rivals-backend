package com.predictrivals.adminMatches

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

enum class MatchStatus {
    scheduled,
    live,
    finished,
}

object AdminMatchesTable : Table("admin_ref.admin_matches") {
    val id = long("id").autoIncrement()
    val externalMatchId = varchar("external_match_id", 64).uniqueIndex()
    val league = varchar("league", 128)
    val homeTeam = varchar("home_team", 128)
    val awayTeam = varchar("away_team", 128)
    val kickoffAt = timestampWithTimeZone("kickoff_at")
    val roundNumber = integer("round_number")
    val status = varchar("status", 16)
    val homeScore = integer("home_score").nullable()
    val awayScore = integer("away_score").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
