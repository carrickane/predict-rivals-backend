package com.predictrivals.adminMatches

import com.predictrivals.common.AdminAuditLogRepository
import com.predictrivals.common.ApiException
import com.predictrivals.footballApi.DateRange
import com.predictrivals.footballApi.FootballDataProvider
import com.predictrivals.live.LiveHub
import com.predictrivals.live.LiveStateService
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.scoring.ScoringService
import com.predictrivals.tournament.TournamentRepository
import com.predictrivals.tournament.requireActive
import com.predictrivals.tournament.requireOwner
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

/** Public fixture search — not tournament-scoped, just needs to be signed in. */
fun Route.fixtureRoutes(footballDataProvider: FootballDataProvider) {
    authenticate(AUTH_JWT) {
        get("/api/fixtures/candidates") {
            val league = call.request.queryParameters["league"]
            val from = call.request.queryParameters["from"]?.let(LocalDate::parse) ?: LocalDate.now()
            val to = call.request.queryParameters["to"]?.let(LocalDate::parse) ?: from.plusDays(7)
            val fixtures = footballDataProvider.searchUpcomingFixtures(league, DateRange(from, to))
            call.respond(
                fixtures.map {
                    FixtureCandidateResponse(it.externalMatchId, it.league, it.homeTeam, it.awayTeam, it.kickoffAt.toString())
                },
            )
        }
    }
}

fun Route.tournamentMatchRoutes(
    adminMatchRepository: AdminMatchRepository,
    roundsRepository: RoundsRepository,
    tournamentRepository: TournamentRepository,
    scoringService: ScoringService,
    auditLogRepository: AdminAuditLogRepository,
    liveStateService: LiveStateService,
    liveHub: LiveHub,
) {
    route("/api/tournaments/{tournamentId}/matches") {
        authenticate(AUTH_JWT) {
            post {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.findById(tournamentId)
                tournament.requireOwner(userId)
                tournament.requireActive()

                val body = call.receive<CreateRoundMatchesRequest>()
                if (body.matches.size != MATCHES_PER_ROUND) {
                    throw ApiException.BadRequest("A round must have exactly $MATCHES_PER_ROUND matches")
                }

                if (roundsRepository.findByTournamentAndNumber(tournamentId, body.roundNumber) == null) {
                    roundsRepository.createIfMissing(tournamentId, body.roundNumber)
                }

                val created = adminMatchRepository.createRoundMatches(tournamentId, body.roundNumber, body.matches)
                call.respond(HttpStatusCode.Created, created.map { it.toResponse() })
            }

            patch("/{matchId}/score") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val matchId = call.parameters["matchId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid match id")

                val tournament = tournamentRepository.findById(tournamentId)
                tournament.requireOwner(userId)

                val body = call.receive<UpdateScoreRequest>()
                val before = adminMatchRepository.getOrThrow(matchId)
                if (before.tournamentId != tournamentId) {
                    throw ApiException.NotFound("Match $matchId not found in tournament $tournamentId")
                }

                val newStatus = body.status?.let {
                    runCatching { MatchStatus.valueOf(it) }.getOrElse {
                        throw ApiException.BadRequest("Invalid status: ${body.status}")
                    }
                } ?: MatchStatus.valueOf(before.status)

                adminMatchRepository.updateScoreAndStatus(matchId, body.homeScore, body.awayScore, newStatus)

                auditLogRepository.record(
                    adminUserId = userId,
                    action = "manual_score_override",
                    targetType = "admin_match",
                    targetId = matchId.toString(),
                    before = "home=${before.homeScore},away=${before.awayScore},status=${before.status}",
                    after = "home=${body.homeScore},away=${body.awayScore},status=${newStatus.name}",
                )

                scoringService.recalculateMatch(matchId)
                liveHub.broadcast(tournamentId, liveStateService.buildCurrentState(tournamentId))

                call.respond(adminMatchRepository.getOrThrow(matchId).toResponse())
            }
        }
    }
}

fun AdminMatchRecord.toResponse() = AdminMatchResponse(
    id = id,
    tournamentId = tournamentId,
    externalMatchId = externalMatchId,
    league = league,
    homeTeam = homeTeam,
    awayTeam = awayTeam,
    kickoffAt = kickoffAt.toString(),
    roundNumber = roundNumber,
    status = status,
    homeScore = homeScore,
    awayScore = awayScore,
)
