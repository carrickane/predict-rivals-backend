package com.predictrivals.footballApi

import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/** Enforces API-Football's free-tier daily request cap; resets at UTC midnight. */
class RequestBudgetTracker(private val dailyLimit: Int = 100) {
    private val logger = LoggerFactory.getLogger(RequestBudgetTracker::class.java)
    private var windowDate: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private var count = 0

    @Synchronized
    fun tryConsume(): Boolean {
        rolloverIfNeeded()
        if (count >= dailyLimit) {
            logger.warn("API-Football daily budget exhausted ($count/$dailyLimit) — skipping request")
            return false
        }
        count++
        if (dailyLimit - count <= 10) {
            logger.warn("API-Football daily budget running low: ${dailyLimit - count} requests remaining today")
        }
        return true
    }

    @Synchronized
    fun remaining(): Int {
        rolloverIfNeeded()
        return (dailyLimit - count).coerceAtLeast(0)
    }

    private fun rolloverIfNeeded() {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (today != windowDate) {
            windowDate = today
            count = 0
        }
    }
}
