package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class PairingResponse(val roundNumber: Int, val opponentUserId: Long?, val opponentName: String?, val isBotMatch: Boolean)

fun Route.pairingsRoutes(pairingsRepository: TournamentPairingsRepository, tournamentRepository: TournamentRepository) {
    authenticate(AUTH_JWT) {
        get("/api/tournaments/{tournamentId}/pairings") {
            val userId = call.principalUserId()
            val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                ?: throw ApiException.BadRequest("Invalid tournament id")
            if (!tournamentRepository.isMember(userId, tournamentId)) {
                throw ApiException.Forbidden("Join the tournament to view its schedule")
            }
            val mine = pairingsRepository.listAllForTournament(tournamentId).filter { it.playerAUserId == userId }
            val opponentIds = mine.mapNotNull { it.playerBUserId }.toSet()
            val names = if (opponentIds.isEmpty()) {
                emptyMap()
            } else {
                dbQuery {
                    UsersTable.selectAll().where { UsersTable.id inList opponentIds }.associate { it[UsersTable.id] to it[UsersTable.name] }
                }
            }
            call.respond(
                mine.map {
                    PairingResponse(
                        roundNumber = it.roundNumber,
                        opponentUserId = it.playerBUserId,
                        opponentName = it.playerBUserId?.let { id -> names[id] },
                        isBotMatch = it.playerBUserId == null,
                    )
                },
            )
        }
    }
}
