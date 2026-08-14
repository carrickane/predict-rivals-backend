package com.predictrivals.auth

import com.predictrivals.common.Hashing
import com.predictrivals.common.JwtService
import java.time.OffsetDateTime

class TokenService(
    private val jwtService: JwtService,
    private val authRepository: AuthRepository,
) {
    suspend fun issueTokenPair(userId: Long, role: String): TokenPair {
        val accessToken = jwtService.issueAccessToken(userId, role)
        val refreshToken = Hashing.randomToken()
        val expiresAt = OffsetDateTime.now().plusSeconds(jwtService.refreshTokenTtlSeconds())
        authRepository.storeRefreshToken(userId, Hashing.sha256Hex(refreshToken), expiresAt)
        return TokenPair(accessToken, refreshToken)
    }

    suspend fun refresh(refreshToken: String): TokenPair {
        val userId = authRepository.consumeRefreshToken(Hashing.sha256Hex(refreshToken))
        val user = authRepository.findUserById(userId) ?: throw com.predictrivals.common.ApiException.Unauthorized()
        return issueTokenPair(user.id, user.role)
    }
}

fun UserRecord.toResponse() = UserResponse(id, name, role)
