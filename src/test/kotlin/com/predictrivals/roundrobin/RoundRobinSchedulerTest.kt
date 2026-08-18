package com.predictrivals.roundrobin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class RoundRobinSchedulerTest : FunSpec({

    test("4 players, 2 legs: 6 matchdays, 2 pairs each, every pair plays twice, no byes") {
        val schedule = RoundRobinScheduler.generate(listOf(1L, 2L, 3L, 4L))
        schedule shouldHaveSize 6
        schedule.forEach { matchday ->
            matchday shouldHaveSize 2
            matchday.forEach { (_, b) -> (b == null) shouldBe false }
        }

        val ids = listOf(1L, 2L, 3L, 4L)
        val expectedPairs = ids.indices.flatMap { i -> (i + 1 until ids.size).map { j -> setOf(ids[i], ids[j]) } }
        val allPairsSeen = schedule.flatMap { matchday -> matchday.map { (a, b) -> setOf(a, b) } }
        expectedPairs.forEach { pair -> allPairsSeen.count { it == pair } shouldBe 2 }
    }

    test("5 players (odd), 2 legs: 10 matchdays, exactly one bye per round, 2 byes per player total") {
        val schedule = RoundRobinScheduler.generate(listOf(1L, 2L, 3L, 4L, 5L))
        schedule shouldHaveSize 10
        schedule.forEach { matchday ->
            matchday shouldHaveSize 3
            matchday.count { (_, b) -> b == null } shouldBe 1
        }

        val byeCounts = (1L..5L).associateWith { id ->
            schedule.count { matchday -> matchday.any { it.first == id && it.second == null } }
        }
        byeCounts.values.forEach { it shouldBe 2 }
    }
})
