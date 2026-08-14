package com.predictrivals.tournament

import kotlinx.serialization.Serializable

@Serializable
data class TournamentResponse(
    val id: Long,
    val name: String,
    val season: String,
    val startDate: String,
    val endDate: String,
)

@Serializable
data class JoinTournamentResponse(val tournamentId: Long, val joinedAt: String)
