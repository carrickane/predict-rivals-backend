package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object RoundRobinStandingsTable : Table("game.round_robin_standings") {
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val userId = long("user_id").references(UsersTable.id)
    val leaguePoints = integer("league_points")
    val wins = integer("wins")
    val draws = integer("draws")
    val losses = integer("losses")
    val goalsFor = integer("goals_for")
    val goalsAgainst = integer("goals_against")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(tournamentId, userId)
}
