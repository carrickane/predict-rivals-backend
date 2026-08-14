package com.predictrivals.live

import kotlinx.serialization.Serializable

@Serializable
data class LiveMatchResponse(
    val id: Long,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: String,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
)

@Serializable
data class LiveStandingEntry(
    val rank: Int,
    val userId: Long,
    val name: String,
    val totalGoals: Int,
    val totalExactScores: Int,
)

@Serializable
data class LiveRoundScoreEntry(
    val userId: Long,
    val name: String,
    val pointsRaw: Int,
    val provisionalGoals: Int,
)

@Serializable
data class LiveStateResponse(
    val matches: List<LiveMatchResponse>,
    val standings: List<LiveStandingEntry>,
    val roundScores: List<LiveRoundScoreEntry>,
)
