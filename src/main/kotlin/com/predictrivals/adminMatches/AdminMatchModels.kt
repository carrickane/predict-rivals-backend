package com.predictrivals.adminMatches

import kotlinx.serialization.Serializable

@Serializable
data class AdminMatchResponse(
    val id: Long,
    val tournamentId: Long,
    val externalMatchId: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: String,
    val roundNumber: Int,
    val status: String,
    val homeScore: Int?,
    val awayScore: Int?,
)

@Serializable
data class NewRoundMatch(
    val externalMatchId: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: String,
)

const val MATCHES_PER_ROUND = 9

@Serializable
data class CreateRoundMatchesRequest(
    val roundNumber: Int,
    val matches: List<NewRoundMatch>,
)

@Serializable
data class FixtureCandidateResponse(
    val externalMatchId: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: String,
)

@Serializable
data class UpdateScoreRequest(val homeScore: Int, val awayScore: Int, val status: String? = null)
