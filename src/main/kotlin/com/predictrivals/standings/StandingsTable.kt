package com.predictrivals.standings

import com.predictrivals.auth.UsersTable
import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object StandingsTable : Table("game.standings") {
    val userId = long("user_id").references(UsersTable.id)
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val totalGoals = integer("total_goals")
    val totalExactScores = integer("total_exact_scores")
    val roundsPlayed = integer("rounds_played")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(userId, tournamentId)
}
