package com.predictrivals.common

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date

const val JWT_CLAIM_USER_ID = "userId"
const val JWT_CLAIM_ROLE = "role"

class JwtService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issueAccessToken(userId: Long, role: String): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(JWT_CLAIM_USER_ID, userId)
            .withClaim(JWT_CLAIM_ROLE, role)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(config.jwtAccessTokenTtlMinutes * 60)))
            .sign(algorithm)
    }

    fun verifier(): JWTVerifier =
        JWT.require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .build()

    fun refreshTokenTtlSeconds(): Long = config.jwtRefreshTokenTtlDays * 24 * 60 * 60
}
