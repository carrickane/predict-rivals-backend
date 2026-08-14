package com.predictrivals.rounds

import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table

enum class RoundStatus {
    scheduled,
    live,
    finished,
}

object RoundsTable : Table("game.rounds") {
    val id = long("id").autoIncrement()
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val roundNumber = integer("round_number")
    val status = varchar("status", 16)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(tournamentId, roundNumber)
    }
}
