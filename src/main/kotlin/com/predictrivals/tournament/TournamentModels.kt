package com.predictrivals.tournament

import com.predictrivals.common.ApiException
import kotlinx.serialization.Serializable

const val MIN_PLAYER_LIMIT = 2
const val MAX_PLAYER_LIMIT = 50

@Serializable
data class CreateTournamentRequest(val name: String, val playerLimit: Int)

@Serializable
data class JoinTournamentRequest(val joinCode: String)

@Serializable
data class TournamentResponse(
    val id: Long,
    val name: String,
    val ownerUserId: Long,
    val joinCode: String,
    val playerLimit: Int,
    val playerCount: Long,
    val format: String,
    val status: String,
    val createdAt: String,
)

fun TournamentRecord.requireOwner(callerUserId: Long) {
    if (ownerUserId != callerUserId) throw ApiException.Forbidden("Only the tournament owner can do this")
}

fun TournamentRecord.requireActive() {
    if (status != TournamentStatus.active.name) throw ApiException.Conflict("Tournament hasn't started yet")
}

fun TournamentRecord.toResponse(playerCount: Long) = TournamentResponse(
    id = id,
    name = name,
    ownerUserId = ownerUserId,
    joinCode = joinCode,
    playerLimit = playerLimit,
    playerCount = playerCount,
    format = format,
    status = status,
    createdAt = createdAt.toString(),
)
