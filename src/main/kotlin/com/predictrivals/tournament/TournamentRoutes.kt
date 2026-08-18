package com.predictrivals.tournament

import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.roundrobin.TournamentPairingsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.tournamentRoutes(tournamentRepository: TournamentRepository, pairingsRepository: TournamentPairingsRepository) {
    route("/api/tournaments") {
        authenticate(AUTH_JWT) {
            post {
                val userId = call.principalUserId()
                val body = call.receive<CreateTournamentRequest>()
                if (body.name.isBlank()) throw ApiException.BadRequest("Tournament name is required")
                if (body.playerLimit !in MIN_PLAYER_LIMIT..MAX_PLAYER_LIMIT) {
                    throw ApiException.BadRequest("Player limit must be between $MIN_PLAYER_LIMIT and $MAX_PLAYER_LIMIT")
                }
                if (body.format !in setOf(TournamentFormat.solo_points.name, TournamentFormat.round_robin.name)) {
                    throw ApiException.BadRequest("format must be one of: solo_points, round_robin")
                }
                val tournament = tournamentRepository.create(body.name, userId, body.playerLimit, body.format)
                call.respond(HttpStatusCode.Created, tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            post("/join") {
                val userId = call.principalUserId()
                val body = call.receive<JoinTournamentRequest>()
                val tournament = tournamentRepository.join(userId, body.joinCode.trim().uppercase())
                if (tournament.status == TournamentStatus.active.name && tournament.format == TournamentFormat.round_robin.name) {
                    pairingsRepository.ensureScheduleGenerated(tournament.id, tournamentRepository.listMemberUserIds(tournament.id))
                }
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            post("/{id}/start") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["id"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.startNow(tournamentId, userId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    pairingsRepository.ensureScheduleGenerated(tournament.id, tournamentRepository.listMemberUserIds(tournament.id))
                }
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            get("/mine") {
                val userId = call.principalUserId()
                val tournaments = tournamentRepository.listForUser(userId)
                call.respond(tournaments.map { it.toResponse(tournamentRepository.memberCount(it.id)) })
            }

            get("/{id}") {
                val tournamentId = call.parameters["id"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.findById(tournamentId)
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }
        }
    }
}
