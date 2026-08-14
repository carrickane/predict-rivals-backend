package com.predictrivals.auth.providers

import com.predictrivals.auth.AuthCredentials
import com.predictrivals.auth.AuthProvider
import com.predictrivals.auth.AuthProviderType
import com.predictrivals.auth.AuthRepository
import com.predictrivals.auth.AuthenticatedUser
import com.predictrivals.common.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

@Serializable
data class GoogleTokenInfo(
    val aud: String,
    val sub: String,
    val email: String? = null,
    val name: String? = null,
)

/** Verifies Google ID tokens via Google's tokeninfo endpoint (server-side verification, never trusts the client). */
class GoogleAuthProvider(
    private val authRepository: AuthRepository,
    private val httpClient: HttpClient,
    private val clientId: String,
) : AuthProvider {
    override val type = AuthProviderType.google

    override suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser {
        val idToken = (credentials as? AuthCredentials.OAuthToken)?.idToken
            ?: throw ApiException.BadRequest("GoogleAuthProvider requires an OAuthToken credential")

        val info = try {
            httpClient.get("https://oauth2.googleapis.com/tokeninfo") {
                parameter("id_token", idToken)
            }.body<GoogleTokenInfo>()
        } catch (e: ResponseException) {
            throw ApiException.Unauthorized("Invalid Google ID token")
        }

        if (info.aud != clientId) throw ApiException.Unauthorized("Google token audience mismatch")

        val user = authRepository.findOrCreateUserForIdentity(
            provider = AuthProviderType.google,
            providerUserId = info.sub,
            name = info.name,
            email = info.email,
            phone = null,
        )
        return AuthenticatedUser(user.id, user.name, user.role)
    }
}
