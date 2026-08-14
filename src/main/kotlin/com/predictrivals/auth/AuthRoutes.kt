package com.predictrivals.auth

import com.predictrivals.auth.providers.EmailPasswordProvider
import com.predictrivals.auth.providers.FacebookAuthProvider
import com.predictrivals.auth.providers.GoogleAuthProvider
import com.predictrivals.plugins.AuthLoginRateLimit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

class AuthProviders(
    val emailPassword: EmailPasswordProvider,
    val google: GoogleAuthProvider,
    val facebook: FacebookAuthProvider,
)

fun Route.authRoutes(providers: AuthProviders, tokenService: TokenService) {
    route("/api/auth") {
        rateLimit(AuthLoginRateLimit) {
            post("/email/register") {
                val body = call.receive<RegisterEmailRequest>()
                val user = providers.emailPassword.authenticate(
                    AuthCredentials.EmailRegister(body.name, body.email, body.password),
                )
                call.respond(HttpStatusCode.Created, buildAuthResponse(user, tokenService))
            }

            post("/email/login") {
                val body = call.receive<LoginEmailRequest>()
                val user = providers.emailPassword.authenticate(
                    AuthCredentials.EmailLogin(body.email, body.password),
                )
                call.respond(buildAuthResponse(user, tokenService))
            }
        }

        post("/google") {
            val body = call.receive<OAuthTokenRequest>()
            val user = providers.google.authenticate(AuthCredentials.OAuthToken(body.idToken))
            call.respond(buildAuthResponse(user, tokenService))
        }

        post("/facebook") {
            val body = call.receive<OAuthTokenRequest>()
            val user = providers.facebook.authenticate(AuthCredentials.OAuthToken(body.idToken))
            call.respond(buildAuthResponse(user, tokenService))
        }

        post("/refresh") {
            val body = call.receive<RefreshTokenRequest>()
            val tokens = tokenService.refresh(body.refreshToken)
            call.respond(tokens)
        }
    }
}

private suspend fun buildAuthResponse(user: AuthenticatedUser, tokenService: TokenService): AuthResponse {
    val tokens = tokenService.issueTokenPair(user.userId, user.role)
    return AuthResponse(tokens, UserResponse(user.userId, user.name, user.role))
}
