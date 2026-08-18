package com.predictrivals

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.fixtureRoutes
import com.predictrivals.adminMatches.tournamentMatchRoutes
import com.predictrivals.auth.AuthProviders
import com.predictrivals.auth.AuthRepository
import com.predictrivals.auth.TokenService
import com.predictrivals.auth.authRoutes
import com.predictrivals.auth.providers.EmailPasswordProvider
import com.predictrivals.auth.providers.FacebookAuthProvider
import com.predictrivals.auth.providers.GoogleAuthProvider
import com.predictrivals.common.AdminAuditLogRepository
import com.predictrivals.common.AppConfig
import com.predictrivals.common.AppHttpClient
import com.predictrivals.common.DatabaseFactory
import com.predictrivals.common.JwtService
import com.predictrivals.footballApi.ApiFootballProvider
import com.predictrivals.footballApi.RequestBudgetTracker
import com.predictrivals.live.LiveHub
import com.predictrivals.live.LiveStateService
import com.predictrivals.live.liveRoutes
import com.predictrivals.liveSync.LiveSyncWorker
import com.predictrivals.plugins.configureCors
import com.predictrivals.plugins.configureErrorHandling
import com.predictrivals.plugins.configureLogging
import com.predictrivals.plugins.configureRateLimiting
import com.predictrivals.plugins.configureSecurity
import com.predictrivals.plugins.configureSerialization
import com.predictrivals.predictions.PredictionsRepository
import com.predictrivals.predictions.predictionsRoutes
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.rounds.calendarRoutes
import com.predictrivals.rounds.roundsRoutes
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.roundrobin.TournamentPairingsRepository
import com.predictrivals.roundrobin.pairingsRoutes
import com.predictrivals.scoring.RoundScoresRepository
import com.predictrivals.scoring.ScoringService
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.standings.standingsRoutes
import com.predictrivals.tournament.TournamentRepository
import com.predictrivals.tournament.tournamentRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    // The app stores everything as TIMESTAMPTZ / OffsetDateTime and must behave identically
    // regardless of the host machine's locale — pin the JVM default to UTC before anything
    // (including the JDBC connection pool) can read the host's timezone setting.
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = AppConfig.fromEnv()
    DatabaseFactory.init(config)

    val httpClient = AppHttpClient.client
    val jwtService = JwtService(config)

    // Repositories
    val authRepository = AuthRepository()
    val tournamentRepository = TournamentRepository()
    val roundsRepository = RoundsRepository()
    val adminMatchRepository = AdminMatchRepository()
    val predictionsRepository = PredictionsRepository()
    val roundScoresRepository = RoundScoresRepository()
    val standingsRepository = StandingsRepository()
    val pairingsRepository = TournamentPairingsRepository()
    val roundRobinStandingsRepository = RoundRobinStandingsRepository(pairingsRepository, roundScoresRepository, roundsRepository)
    val auditLogRepository = AdminAuditLogRepository()

    // Auth
    val tokenService = TokenService(jwtService, authRepository)
    val authProviders = AuthProviders(
        emailPassword = EmailPasswordProvider(authRepository),
        google = GoogleAuthProvider(authRepository, httpClient, config.googleClientId),
        facebook = FacebookAuthProvider(authRepository, httpClient, config.facebookAppId, config.facebookAppSecret),
    )

    // Football data + scoring
    val requestBudgetTracker = RequestBudgetTracker()
    val footballDataProvider = ApiFootballProvider(httpClient, config.apiFootballBaseUrl, config.apiFootballKey, requestBudgetTracker)
    val scoringService = ScoringService(
        predictionsRepository,
        roundScoresRepository,
        adminMatchRepository,
        roundsRepository,
        tournamentRepository,
        pairingsRepository,
        roundRobinStandingsRepository,
    )

    // Live
    val liveStateService = LiveStateService(
        roundsRepository,
        adminMatchRepository,
        standingsRepository,
        roundRobinStandingsRepository,
        roundScoresRepository,
        tournamentRepository,
    )
    val liveHub = LiveHub()

    // Plugins
    configureSerialization()
    configureCors(config)
    configureLogging()
    configureErrorHandling()
    configureRateLimiting()
    configureSecurity(jwtService)
    install(WebSockets)

    routing {
        authRoutes(authProviders, tokenService)
        tournamentRoutes(tournamentRepository, pairingsRepository)
        roundsRoutes(roundsRepository, tournamentRepository)
        calendarRoutes(roundsRepository, adminMatchRepository, tournamentRepository)
        fixtureRoutes(footballDataProvider)
        tournamentMatchRoutes(adminMatchRepository, roundsRepository, tournamentRepository, pairingsRepository, scoringService, auditLogRepository, liveStateService, liveHub)
        predictionsRoutes(predictionsRepository, adminMatchRepository, tournamentRepository, roundsRepository)
        standingsRoutes(standingsRepository, roundRobinStandingsRepository, tournamentRepository)
        liveRoutes(liveStateService, liveHub, tournamentRepository, jwtService)
        pairingsRoutes(pairingsRepository, tournamentRepository)
    }

    val liveSyncWorker = LiveSyncWorker(adminMatchRepository, footballDataProvider, scoringService, liveStateService, liveHub)
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    liveSyncWorker.start(backgroundScope)
}
