package com.predictrivals.scoring

/**
 * points is the raw contribution toward the round's 3-points-=-1-goal conversion; it is always 0
 * when isExact is true, since an exact score awards a goal directly instead of points.
 */
data class PredictionScore(val points: Int, val isExact: Boolean)

private enum class Outcome { HOME_WIN, AWAY_WIN, DRAW }

/**
 * Pure scoring rules per the design doc section 5:
 *  - exact score (any outcome, including draws): 1 goal, direct
 *  - correct win/loss outcome + correct goal difference (not exact): 2 points
 *  - correct win/loss outcome, wrong goal difference: 1 point
 *  - correct draw, wrong exact score: 1 point, always (never 2)
 *  - wrong outcome: 0
 */
object ScoringEngine {

    fun score(predictedHome: Int, predictedAway: Int, actualHome: Int, actualAway: Int): PredictionScore {
        if (predictedHome == actualHome && predictedAway == actualAway) {
            return PredictionScore(points = 0, isExact = true)
        }

        val predictedOutcome = outcomeOf(predictedHome, predictedAway)
        val actualOutcome = outcomeOf(actualHome, actualAway)

        if (predictedOutcome != actualOutcome) {
            return PredictionScore(points = 0, isExact = false)
        }

        if (actualOutcome == Outcome.DRAW) {
            return PredictionScore(points = 1, isExact = false)
        }

        val predictedDiff = predictedHome - predictedAway
        val actualDiff = actualHome - actualAway
        val points = if (predictedDiff == actualDiff) 2 else 1
        return PredictionScore(points = points, isExact = false)
    }

    /** Round-level conversion: points below the next multiple of 3 are discarded, never carried to the next round. */
    fun convertToGoals(pointsRaw: Int, exactCount: Int): Int = (pointsRaw / 3) + exactCount

    private fun outcomeOf(home: Int, away: Int): Outcome = when {
        home > away -> Outcome.HOME_WIN
        home < away -> Outcome.AWAY_WIN
        else -> Outcome.DRAW
    }
}
