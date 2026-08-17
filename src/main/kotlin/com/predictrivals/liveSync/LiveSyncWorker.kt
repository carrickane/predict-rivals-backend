package com.predictrivals.liveSync

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.MatchStatus
import com.predictrivals.footballApi.FootballDataProvider
import com.predictrivals.footballApi.NormalTimeStatuses
import com.predictrivals.live.LiveHub
import com.predictrivals.live.LiveStateService
import com.predictrivals.scoring.ScoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.minutes

/** Fixture states that mean "hasn't actually kicked off yet" (provider-side delay/postponement) — never finalized here. */
private val PRE_MATCH_STATUSES = setOf("TBD", "NS", "PST", "CANC", "ABD", "SUSP", "INT")
private val POLL_INTERVAL = 5.minutes

/**
 * Background polling loop per design doc sections 7-8:
 *  - flips scheduled matches to live once kickoff passes
 *  - polls live matches every 5 minutes, only while they're in normal match time
 *  - the instant a match's provider status leaves normal time (FT, AET, penalties, ...),
 *    freezes the score exactly as last polled and marks the match finished — extra time
 *    and penalty shootouts are never polled or scored
 *
 * Different tournaments can independently feature the same real-world fixture, so live matches
 * are grouped by `externalMatchId` before polling — one API request per real fixture regardless
 * of how many tournaments include it, and a score update applies to every tournament's copy.
 */
class LiveSyncWorker(
    private val adminMatchRepository: AdminMatchRepository,
    private val footballDataProvider: FootballDataProvider,
    private val scoringService: ScoringService,
    private val liveStateService: LiveStateService,
    private val liveHub: LiveHub,
) {
    private val logger = LoggerFactory.getLogger(LiveSyncWorker::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { tick() }.onFailure { logger.error("Live sync tick failed", it) }
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun tick() {
        flipScheduledToLive()
        pollLiveMatches()
    }

    private suspend fun flipScheduledToLive() {
        val now = OffsetDateTime.now()
        adminMatchRepository.listByStatus(MatchStatus.scheduled)
            .filter { it.kickoffAt.isBefore(now) }
            .forEach { match ->
                adminMatchRepository.updateScoreAndStatus(match.id, match.homeScore, match.awayScore, MatchStatus.live)
            }
    }

    private suspend fun pollLiveMatches() {
        val liveMatches = adminMatchRepository.listByStatus(MatchStatus.live)
        if (liveMatches.isEmpty()) return

        val byExternalId = liveMatches.groupBy { it.externalMatchId }
        val liveScores = footballDataProvider.getLiveScores(byExternalId.keys.toList())
        val changedTournamentIds = mutableSetOf<Long>()

        liveScores.forEach { liveScore ->
            val matchesForThisFixture = byExternalId[liveScore.externalMatchId] ?: return@forEach

            when {
                liveScore.providerStatus in PRE_MATCH_STATUSES -> Unit // not actually underway yet, leave as-is

                NormalTimeStatuses.isNormalTime(liveScore.providerStatus) -> {
                    matchesForThisFixture.forEach { match ->
                        if (liveScore.homeScore != match.homeScore || liveScore.awayScore != match.awayScore) {
                            adminMatchRepository.updateScoreAndStatus(match.id, liveScore.homeScore, liveScore.awayScore, MatchStatus.live)
                            scoringService.recalculateMatch(match.id)
                            changedTournamentIds += match.tournamentId
                        }
                    }
                }

                else -> {
                    matchesForThisFixture.forEach { match ->
                        adminMatchRepository.updateScoreAndStatus(match.id, liveScore.homeScore, liveScore.awayScore, MatchStatus.finished)
                        scoringService.recalculateMatch(match.id)
                        changedTournamentIds += match.tournamentId
                    }
                }
            }
        }

        changedTournamentIds.forEach { tournamentId ->
            liveHub.broadcast(tournamentId, liveStateService.buildCurrentState(tournamentId))
        }
    }
}
