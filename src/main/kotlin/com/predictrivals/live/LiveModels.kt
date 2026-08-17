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
    val totalPoints: Int,
    val exactCount: Int,
)

@Serializable
data class LiveRoundScoreEntry(
    val userId: Long,
    val name: String,
    val roundPoints: Int,
)

@Serializable
data class LiveStateResponse(
    val matches: List<LiveMatchResponse>,
    val standings: List<LiveStandingEntry>,
    val roundScores: List<LiveRoundScoreEntry>,
)
