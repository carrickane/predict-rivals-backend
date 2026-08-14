package com.predictrivals.rounds

import kotlinx.serialization.Serializable

@Serializable
data class RoundResponse(
    val id: Long,
    val tournamentId: Long,
    val roundNumber: Int,
    val status: String,
)
