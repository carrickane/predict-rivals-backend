package com.predictrivals.adminMatches

import com.predictrivals.common.AdminAuditLogRepository
import com.predictrivals.common.ApiException
import com.predictrivals.footballApi.DateRange
import com.predictrivals.footballApi.FootballDataProvider
import com.predictrivals.live.LiveHub
import com.predictrivals.live.LiveStateService
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.plugins.requireAdmin
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.scoring.ScoringService
import com.predictrivals.tournament.TournamentRepository
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

fun Route.adminMatchRoutes(
    adminMatchRepository: AdminMatchRepository,
    roundsRepository: RoundsRepository,
    tournamentRepository: TournamentRepository,
    footballDataProvider: FootballDataProvider,
    scoringService: ScoringService,
    auditLogRepository: AdminAuditLogRepository,
    liveStateService: LiveStateService,
    liveHub: LiveHub,
) {
    route("/api/admin") {
        authenticate(AUTH_JWT) {
            get("/fixtures/candidates") {
                call.requireAdmin()
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

            post("/matches") {
                call.requireAdmin()
                val body = call.receive<CreateRoundMatchesRequest>()
                if (body.matches.size != MATCHES_PER_ROUND) {
                    throw ApiException.BadRequest("A round must have exactly $MATCHES_PER_ROUND matches")
                }

                val tournament = tournamentRepository.findActiveTournament()
                if (roundsRepository.findByTournamentAndNumber(tournament.id, body.roundNumber) == null) {
                    roundsRepository.createIfMissing(tournament.id, body.roundNumber)
                }

                val created = adminMatchRepository.createRoundMatches(body.roundNumber, body.matches)
                call.respond(HttpStatusCode.Created, created.map { it.toResponse() })
            }

            patch("/matches/{id}/score") {
                call.requireAdmin()
                val adminUserId = call.principalUserId()
                val matchId = call.parameters["id"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid match id")
                val body = call.receive<UpdateScoreRequest>()

                val before = adminMatchRepository.getOrThrow(matchId)
                val newStatus = body.status?.let {
                    runCatching { MatchStatus.valueOf(it) }.getOrElse {
                        throw ApiException.BadRequest("Invalid status: ${body.status}")
                    }
                } ?: MatchStatus.valueOf(before.status)

                adminMatchRepository.updateScoreAndStatus(matchId, body.homeScore, body.awayScore, newStatus)

                auditLogRepository.record(
                    adminUserId = adminUserId,
                    action = "manual_score_override",
                    targetType = "admin_match",
                    targetId = matchId.toString(),
                    before = "home=${before.homeScore},away=${before.awayScore},status=${before.status}",
                    after = "home=${body.homeScore},away=${body.awayScore},status=${newStatus.name}",
                )

                scoringService.recalculateMatch(matchId)
                liveHub.broadcast(liveStateService.buildCurrentState())

                call.respond(adminMatchRepository.getOrThrow(matchId).toResponse())
            }
        }
    }
}

fun AdminMatchRecord.toResponse() = AdminMatchResponse(
    id = id,
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
