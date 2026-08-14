package com.predictrivals.predictions

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.OffsetDateTime

fun Route.predictionsRoutes(
    predictionsRepository: PredictionsRepository,
    adminMatchRepository: AdminMatchRepository,
    tournamentRepository: TournamentRepository,
    roundsRepository: RoundsRepository,
) {
    route("/api/predictions") {
        authenticate(AUTH_JWT) {
            post {
                val userId = call.principalUserId()
                val body = call.receive<SubmitPredictionRequest>()

                if (body.homeScore !in 0..20 || body.awayScore !in 0..20) {
                    throw ApiException.BadRequest("Predicted scores must be between 0 and 20")
                }

                val tournament = tournamentRepository.findActiveTournament()
                if (!tournamentRepository.isMember(userId, tournament.id)) {
                    throw ApiException.Forbidden("Join the tournament before submitting predictions")
                }

                val match = adminMatchRepository.getOrThrow(body.matchId)
                val round = roundsRepository.findByTournamentAndNumber(tournament.id, match.roundNumber)
                    ?: throw ApiException.NotFound("Round for match ${body.matchId} not found")

                // Defense in depth: the client should stop allowing edits at kickoff, but if a
                // request lands after it anyway, the write is still recorded (for transparency)
                // and simply excluded from scoring — see design doc section 5.
                val isLate = OffsetDateTime.now().isAfter(match.kickoffAt)

                val prediction = predictionsRepository.upsert(
                    userId = userId,
                    matchId = match.id,
                    roundId = round.id,
                    homeScore = body.homeScore,
                    awayScore = body.awayScore,
                    isLate = isLate,
                )
                call.respond(prediction.toResponse())
            }
        }
    }
}
