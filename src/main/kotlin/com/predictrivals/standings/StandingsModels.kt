package com.predictrivals.standings

import kotlinx.serialization.Serializable

@Serializable
data class StandingEntryResponse(
    val rank: Int,
    val userId: Long,
    val name: String,
    val totalGoals: Int,
    val totalExactScores: Int,
    val roundsPlayed: Int,
)

fun List<StandingRow>.toRanked(): List<StandingEntryResponse> =
    mapIndexed { index, row ->
        StandingEntryResponse(index + 1, row.userId, row.name, row.totalGoals, row.totalExactScores, row.roundsPlayed)
    }

@Serializable
data class TopScorerEntryResponse(val rank: Int, val userId: Long, val name: String, val totalGoals: Int)

@Serializable
data class UserStatsResponse(
    val userId: Long,
    val name: String,
    val totalGoals: Int,
    val totalExactScores: Int,
    val roundsPlayed: Int,
    val totalPredictions: Int,
    val scoredPredictions: Int,
    val accuracy: Double,
)
