package com.predictrivals.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/**
 * Named limiters applied to specific routes in the auth module to blunt
 * brute-force login attempts and SMS-bombing / OTP-guessing abuse.
 */
val AuthLoginRateLimit = RateLimitName("auth-login")
val SmsRequestRateLimit = RateLimitName("auth-sms-request")
val SmsVerifyRateLimit = RateLimitName("auth-sms-verify")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(AuthLoginRateLimit) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
        register(SmsRequestRateLimit) {
            rateLimiter(limit = 3, refillPeriod = 10.minutes)
        }
        register(SmsVerifyRateLimit) {
            rateLimiter(limit = 5, refillPeriod = 10.minutes)
        }
    }
}
