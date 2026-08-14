package com.predictrivals.predictions

import com.predictrivals.adminMatches.AdminMatchesTable
import com.predictrivals.auth.UsersTable
import com.predictrivals.rounds.RoundsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object PredictionsTable : Table("game.predictions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val matchId = long("match_id").references(AdminMatchesTable.id)
    val roundId = long("round_id").references(RoundsTable.id)
    val predictedHomeScore = integer("predicted_home_score")
    val predictedAwayScore = integer("predicted_away_score")
    val submittedAt = timestampWithTimeZone("submitted_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val isLate = bool("is_late")
    val pointsAwarded = integer("points_awarded").nullable()
    val isExact = bool("is_exact").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, matchId)
    }
}
