package com.predictrivals.standings

import com.predictrivals.roundrobin.RoundRobinStandingRow
import kotlinx.serialization.Serializable

@Serializable
data class RoundRobinStandingEntryResponse(
    val rank: Int,
    val userId: Long,
    val name: String,
    val leaguePoints: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
)

fun List<RoundRobinStandingRow>.toRankedRoundRobin(): List<RoundRobinStandingEntryResponse> =
    mapIndexed { index, row ->
        RoundRobinStandingEntryResponse(
            rank = index + 1,
            userId = row.userId,
            name = row.name,
            leaguePoints = row.leaguePoints,
            wins = row.wins,
            draws = row.draws,
            losses = row.losses,
            goalsFor = row.goalsFor,
            goalsAgainst = row.goalsAgainst,
            goalDifference = row.goalsFor - row.goalsAgainst,
        )
    }

@Serializable
data class RoundRobinTopScorerEntryResponse(val rank: Int, val userId: Long, val name: String, val goalsFor: Int)

fun List<RoundRobinStandingRow>.toTopScorersRoundRobin(): List<RoundRobinTopScorerEntryResponse> =
    sortedByDescending { it.goalsFor }
        .mapIndexed { index, row -> RoundRobinTopScorerEntryResponse(index + 1, row.userId, row.name, row.goalsFor) }

// --- solo_points format ---

@Serializable
data class SoloStandingEntryResponse(
    val rank: Int,
    val userId: Long,
    val name: String,
    val totalPoints: Int,
    val exactCount: Int,
)

fun List<SoloStandingRow>.toRankedSolo(): List<SoloStandingEntryResponse> =
    mapIndexed { index, row -> SoloStandingEntryResponse(index + 1, row.userId, row.name, row.totalPoints, row.exactCount) }

@Serializable
data class SoloUserStatsResponse(
    val userId: Long,
    val name: String,
    val totalPoints: Int,
    val exactCount: Int,
    val totalPredictions: Int,
    val scoredPredictions: Int,
    val accuracy: Double,
)

fun UserTournamentStats.toResponse() =
    SoloUserStatsResponse(userId, name, totalPoints, exactCount, totalPredictions, scoredPredictions, accuracy)
