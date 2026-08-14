package com.predictrivals.scoring

import com.predictrivals.auth.UsersTable
import com.predictrivals.rounds.RoundsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object RoundScoresTable : Table("game.round_scores") {
    val userId = long("user_id").references(UsersTable.id)
    val roundId = long("round_id").references(RoundsTable.id)
    val pointsRaw = integer("points_raw")
    val exactCount = integer("exact_count")
    val goalsAwarded = integer("goals_awarded")
    val isFrozen = bool("is_frozen")
    val computedAt = timestampWithTimeZone("computed_at")

    override val primaryKey = PrimaryKey(userId, roundId)
}
