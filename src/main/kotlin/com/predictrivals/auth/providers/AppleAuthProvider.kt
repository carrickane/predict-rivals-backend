package com.predictrivals.auth.providers

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.predictrivals.auth.AuthCredentials
import com.predictrivals.auth.AuthProvider
import com.predictrivals.auth.AuthProviderType
import com.predictrivals.auth.AuthRepository
import com.predictrivals.auth.AuthenticatedUser
import com.predictrivals.common.ApiException
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

private const val APPLE_ISSUER = "https://appleid.apple.com"

/** Verifies Apple identity tokens against Apple's published JWKs (never trusts a client-supplied identity). */
class AppleAuthProvider(
    private val authRepository: AuthRepository,
    private val clientId: String,
) : AuthProvider {
    override val type = AuthProviderType.apple

    private val jwkProvider = JwkProviderBuilder(APPLE_ISSUER)
        .cached(10, 12, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser {
        val idToken = (credentials as? AuthCredentials.OAuthToken)?.idToken
            ?: throw ApiException.BadRequest("AppleAuthProvider requires an OAuthToken credential")

        val decoded = try {
            JWT.decode(idToken)
        } catch (e: Exception) {
            throw ApiException.Unauthorized("Invalid Apple identity token")
        }

        val verified = try {
            val jwk = jwkProvider.get(decoded.keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
            JWT.require(algorithm)
                .withIssuer(APPLE_ISSUER)
                .withAudience(clientId)
                .build()
                .verify(idToken)
        } catch (e: JWTVerificationException) {
            throw ApiException.Unauthorized("Apple identity token verification failed")
        }

        val subject = verified.subject
            ?: throw ApiException.Unauthorized("Apple identity token missing subject")
        val email = verified.getClaim("email").asString()

        val user = authRepository.findOrCreateUserForIdentity(
            provider = AuthProviderType.apple,
            providerUserId = subject,
            name = null,
            email = email,
            phone = null,
        )
        return AuthenticatedUser(user.id, user.name, user.role)
    }
}
