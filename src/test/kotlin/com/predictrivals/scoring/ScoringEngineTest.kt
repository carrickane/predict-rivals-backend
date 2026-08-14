package com.predictrivals.scoring

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScoringEngineTest : FunSpec({

    test("exact score win awards a direct goal, no points") {
        val result = ScoringEngine.score(predictedHome = 2, predictedAway = 1, actualHome = 2, actualAway = 1)
        result shouldBe PredictionScore(points = 0, isExact = true)
    }

    test("exact score draw awards a direct goal, no points") {
        val result = ScoringEngine.score(predictedHome = 1, predictedAway = 1, actualHome = 1, actualAway = 1)
        result shouldBe PredictionScore(points = 0, isExact = true)
    }

    test("correct win outcome and correct goal difference, not exact, awards 2 points") {
        // predicted 2:1 (diff +1), actual 3:2 (diff +1) - same outcome, same difference, different score
        val result = ScoringEngine.score(predictedHome = 2, predictedAway = 1, actualHome = 3, actualAway = 2)
        result shouldBe PredictionScore(points = 2, isExact = false)
    }

    test("correct win outcome, wrong goal difference, awards 1 point") {
        // predicted 2:1 (diff +1), actual 3:1 (diff +2) - same outcome, different difference
        val result = ScoringEngine.score(predictedHome = 2, predictedAway = 1, actualHome = 3, actualAway = 1)
        result shouldBe PredictionScore(points = 1, isExact = false)
    }

    test("correct away win outcome, wrong goal difference, awards 1 point") {
        val result = ScoringEngine.score(predictedHome = 0, predictedAway = 1, actualHome = 0, actualAway = 3)
        result shouldBe PredictionScore(points = 1, isExact = false)
    }

    test("correct draw, wrong exact score, always awards exactly 1 point") {
        // predicted 1:1, actual 2:2 - both draws (diff 0 both), but capped at 1 point, never 2
        val result = ScoringEngine.score(predictedHome = 1, predictedAway = 1, actualHome = 2, actualAway = 2)
        result shouldBe PredictionScore(points = 1, isExact = false)
    }

    test("wrong outcome awards nothing") {
        val result = ScoringEngine.score(predictedHome = 1, predictedAway = 0, actualHome = 0, actualAway = 1)
        result shouldBe PredictionScore(points = 0, isExact = false)
    }

    test("wrong outcome (predicted draw, actual decisive) awards nothing") {
        val result = ScoringEngine.score(predictedHome = 1, predictedAway = 1, actualHome = 2, actualAway = 1)
        result shouldBe PredictionScore(points = 0, isExact = false)
    }

    test("convertToGoals floors points below the next multiple of 3, discarding the remainder") {
        ScoringEngine.convertToGoals(pointsRaw = 0, exactCount = 0) shouldBe 0
        ScoringEngine.convertToGoals(pointsRaw = 2, exactCount = 0) shouldBe 0
        ScoringEngine.convertToGoals(pointsRaw = 3, exactCount = 0) shouldBe 1
        ScoringEngine.convertToGoals(pointsRaw = 5, exactCount = 0) shouldBe 1
        ScoringEngine.convertToGoals(pointsRaw = 5, exactCount = 1) shouldBe 2
        ScoringEngine.convertToGoals(pointsRaw = 0, exactCount = 3) shouldBe 3
    }
})
