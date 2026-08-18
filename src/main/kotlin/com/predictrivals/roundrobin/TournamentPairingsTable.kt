package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table

object TournamentPairingsTable : Table("game.tournament_pairings") {
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val roundNumber = integer("round_number")
    val playerAUserId = long("player_a_user_id").references(UsersTable.id)
    val playerBUserId = long("player_b_user_id").references(UsersTable.id).nullable()

    override val primaryKey = PrimaryKey(tournamentId, roundNumber, playerAUserId)
}
