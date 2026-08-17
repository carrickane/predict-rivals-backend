package com.predictrivals.scoring

import com.predictrivals.adminMatches.AdminMatchRecord
import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.MatchStatus
import com.predictrivals.predictions.PredictionsRepository
import com.predictrivals.rounds.RoundRecord
import com.predictrivals.rounds.RoundStatus
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentRepository

/**
 * Bridges the pure ScoringEngine to persistence: recalculates predictions when a match's score
 * changes, and keeps the round's lifecycle status (scheduled -> live -> finished) in sync for
 * every format — that status is what "current round" detection relies on. For `solo_points`
 * tournaments that's the whole job — no round_scores write, no standings write, since standings
 * are computed live from predictions directly (see StandingsRepository.getSoloStandings). The
 * round_scores-freeze-then-apply-to-standings path below is reserved for round_robin/playoff
 * (phase 2), where a round's goals become a head-to-head match score and genuinely need to be
 * frozen once the round finishes.
 */
class ScoringService(
    private val predictionsRepository: PredictionsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val roundsRepository: RoundsRepository,
    private val tournamentRepository: TournamentRepository,
    private val standingsRepository: StandingsRepository,
) {

    /** Called whenever a match's score changes — from a live poll update or a manual owner override. */
    suspend fun recalculateMatch(matchId: Long) {
        val match = adminMatchRepository.getOrThrow(matchId)
        val tournament = tournamentRepository.findById(match.tournamentId)
        val round = roundsRepository.findByTournamentAndNumber(tournament.id, match.roundNumber) ?: return
        val wasAlreadyFinished = round.status == RoundStatus.finished.name

        val homeScore = match.homeScore
        val awayScore = match.awayScore
        val affectedUserIds = mutableListOf<Long>()

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
            affectedUserIds += predictions.map { it.userId }.distinct()
        }

        val roundMatches = adminMatchRepository.listByTournamentAndRound(round.tournamentId, round.roundNumber)
        updateRoundLifecycleStatus(round, roundMatches)

        if (tournament.format == TournamentFormat.solo_points.name) return

        affectedUserIds.forEach { userId -> recomputeRoundScore(round.id, userId) }

        val nowAllFinished = roundMatches.isNotEmpty() && roundMatches.all { it.status == MatchStatus.finished.name }
        if (!wasAlreadyFinished && nowAllFinished) {
            roundScoresRepository.freeze(round.id)
            standingsRepository.applyFinishedRound(round.tournamentId, round.id)
        }
    }

    /** Keeps round.status in sync with its matches' progress, for every tournament format. */
    private suspend fun updateRoundLifecycleStatus(round: RoundRecord, roundMatches: List<AdminMatchRecord>) {
        if (round.status == RoundStatus.finished.name) return
        val allFinished = roundMatches.isNotEmpty() && roundMatches.all { it.status == MatchStatus.finished.name }
        val anyUnderway = roundMatches.any { it.status != MatchStatus.scheduled.name }

        when {
            allFinished -> roundsRepository.updateStatus(round.id, RoundStatus.finished)
            anyUnderway && round.status == RoundStatus.scheduled.name -> roundsRepository.updateStatus(round.id, RoundStatus.live)
        }
    }

    private suspend fun recomputeRoundScore(roundId: Long, userId: Long) {
        val predictions = predictionsRepository.listByRoundAndUser(roundId, userId).filter { !it.isLate }
        val pointsRaw = predictions.sumOf { it.pointsAwarded ?: 0 }
        val exactCount = predictions.count { it.isExact == true }
        val goals = ScoringEngine.convertToGoals(pointsRaw, exactCount)
        roundScoresRepository.upsert(userId, roundId, pointsRaw, exactCount, goals)
    }
}
