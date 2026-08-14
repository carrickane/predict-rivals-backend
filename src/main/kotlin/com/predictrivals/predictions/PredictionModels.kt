package com.predictrivals.predictions

import kotlinx.serialization.Serializable

@Serializable
data class SubmitPredictionRequest(val matchId: Long, val homeScore: Int, val awayScore: Int)

@Serializable
data class PredictionResponse(
    val id: Long,
    val matchId: Long,
    val roundId: Long,
    val homeScore: Int,
    val awayScore: Int,
    val submittedAt: String,
    val isLate: Boolean,
    val pointsAwarded: Int?,
    val isExact: Boolean?,
)

fun PredictionRecord.toResponse() = PredictionResponse(
    id = id,
    matchId = matchId,
    roundId = roundId,
    homeScore = predictedHomeScore,
    awayScore = predictedAwayScore,
    submittedAt = submittedAt.toString(),
    isLate = isLate,
    pointsAwarded = pointsAwarded,
    isExact = isExact,
)
