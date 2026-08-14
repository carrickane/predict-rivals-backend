package com.predictrivals.auth

import kotlinx.serialization.Serializable

data class AuthenticatedUser(
    val userId: Long,
    val name: String,
    val role: String,
)

sealed interface AuthCredentials {
    data class EmailRegister(val name: String, val email: String, val password: String) : AuthCredentials
    data class EmailLogin(val email: String, val password: String) : AuthCredentials
    data class OAuthToken(val idToken: String) : AuthCredentials
    data class PhoneVerify(val phone: String, val code: String) : AuthCredentials
}

interface AuthProvider {
    val type: AuthProviderType
    suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser
}

@Serializable
data class TokenPair(val accessToken: String, val refreshToken: String)

@Serializable
data class UserResponse(val id: Long, val name: String, val role: String)

@Serializable
data class AuthResponse(val tokens: TokenPair, val user: UserResponse)

@Serializable
data class RegisterEmailRequest(val name: String, val email: String, val password: String)

@Serializable
data class LoginEmailRequest(val email: String, val password: String)

@Serializable
data class OAuthTokenRequest(val idToken: String)

@Serializable
data class PhoneRequestCodeRequest(val phone: String)

@Serializable
data class PhoneVerifyCodeRequest(val phone: String, val code: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)
