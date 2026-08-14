package com.predictrivals.scoring

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.MatchStatus
import com.predictrivals.predictions.PredictionsRepository
import com.predictrivals.rounds.RoundRecord
import com.predictrivals.rounds.RoundStatus
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.tournament.TournamentRepository

/**
 * Bridges the pure ScoringEngine to persistence: recalculates predictions and round_scores when
 * a match's score changes, and freezes+applies-to-standings a round once all of its matches
 * finish (see design doc sections 5 and 8).
 */
class ScoringService(
    private val predictionsRepository: PredictionsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val roundsRepository: RoundsRepository,
    private val tournamentRepository: TournamentRepository,
    private val standingsRepository: StandingsRepository,
) {

    /** Called whenever a match's score changes — from a live poll update or a manual admin override. */
    suspend fun recalculateMatch(matchId: Long) {
        val match = adminMatchRepository.getOrThrow(matchId)
        val tournament = tournamentRepository.findActiveTournament()
        val round = roundsRepository.findByTournamentAndNumber(tournament.id, match.roundNumber) ?: return

        val homeScore = match.homeScore
        val awayScore = match.awayScore
        if (homeScore != null && awayScore != null) {
            val predictions = predictionsRepository.listByMatch(matchId).filter { !it.isLate }
            predictions.forEach { prediction ->
                val result = ScoringEngine.score(
                    predictedHome = prediction.predictedHomeScore,
                    predictedAway = prediction.predictedAwayScore,
                    actualHome = homeScore,
                    actualAway = awayScore,
                )
                predictionsRepository.updateScore(prediction.id, result.points, result.isExact)
            }
            predictions.map { it.userId }.distinct().forEach { userId -> recomputeRoundScore(round.id, userId) }
        }

        if (match.status == MatchStatus.finished.name) {
            maybeFreezeRound(round)
        }
    }

    private suspend fun recomputeRoundScore(roundId: Long, userId: Long) {
        val predictions = predictionsRepository.listByRoundAndUser(roundId, userId).filter { !it.isLate }
        val pointsRaw = predictions.sumOf { it.pointsAwarded ?: 0 }
        val exactCount = predictions.count { it.isExact == true }
        val goals = ScoringEngine.convertToGoals(pointsRaw, exactCount)
        roundScoresRepository.upsert(userId, roundId, pointsRaw, exactCount, goals)
    }

    private suspend fun maybeFreezeRound(round: RoundRecord) {
        // Idempotency guard: once a round is finished, never re-apply its goals to standings.
        if (round.status == RoundStatus.finished.name) return

        val matches = adminMatchRepository.listByRoundNumber(round.roundNumber)
        if (matches.isEmpty() || matches.any { it.status != MatchStatus.finished.name }) return

        roundScoresRepository.freeze(round.id)
        roundsRepository.updateStatus(round.id, RoundStatus.finished)
        standingsRepository.applyFinishedRound(round.tournamentId, round.id)
    }
}
