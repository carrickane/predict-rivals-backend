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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FacebookDebugTokenData(
    @SerialName("is_valid") val isValid: Boolean,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("app_id") val appId: String? = null,
)

@Serializable
data class FacebookDebugTokenResponse(val data: FacebookDebugTokenData)

@Serializable
data class FacebookProfile(val id: String, val name: String? = null, val email: String? = null)

/** Verifies Facebook access tokens via the Graph API's debug_token endpoint before trusting the profile it names. */
class FacebookAuthProvider(
    private val authRepository: AuthRepository,
    private val httpClient: HttpClient,
    private val appId: String,
    private val appSecret: String,
) : AuthProvider {
    override val type = AuthProviderType.facebook

    override suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser {
        val accessToken = (credentials as? AuthCredentials.OAuthToken)?.idToken
            ?: throw ApiException.BadRequest("FacebookAuthProvider requires an OAuthToken credential")

        val debug = try {
            httpClient.get("https://graph.facebook.com/debug_token") {
                parameter("input_token", accessToken)
                parameter("access_token", "$appId|$appSecret")
            }.body<FacebookDebugTokenResponse>()
        } catch (e: ResponseException) {
            throw ApiException.Unauthorized("Invalid Facebook access token")
        }

        if (!debug.data.isValid || debug.data.appId != appId || debug.data.userId == null) {
            throw ApiException.Unauthorized("Invalid Facebook access token")
        }

        val profile = httpClient.get("https://graph.facebook.com/${debug.data.userId}") {
            parameter("fields", "name,email")
            parameter("access_token", accessToken)
        }.body<FacebookProfile>()

        val user = authRepository.findOrCreateUserForIdentity(
            provider = AuthProviderType.facebook,
            providerUserId = profile.id,
            name = profile.name,
            email = profile.email,
        )
        return AuthenticatedUser(user.id, user.name, user.role)
    }
}
