package com.predictrivals.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/** Applied to the email login/register routes to blunt brute-force and credential-stuffing attempts. */
val AuthLoginRateLimit = RateLimitName("auth-login")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(AuthLoginRateLimit) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
    }
}
