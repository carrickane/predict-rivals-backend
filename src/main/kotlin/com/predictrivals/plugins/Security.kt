package com.predictrivals.plugins

import com.predictrivals.common.ApiException
import com.predictrivals.common.JWT_CLAIM_ROLE
import com.predictrivals.common.JWT_CLAIM_USER_ID
import com.predictrivals.common.JwtService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal

const val AUTH_JWT = "auth-jwt"

fun Application.configureSecurity(jwtService: JwtService) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier(jwtService.verifier())
            validate { credential ->
                val userId = credential.payload.getClaim(JWT_CLAIM_USER_ID).asLong()
                val role = credential.payload.getClaim(JWT_CLAIM_ROLE).asString()
                if (userId != null && role != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
}

fun ApplicationCall.principalUserId(): Long =
    principal<JWTPrincipal>()
        ?.payload
        ?.getClaim(JWT_CLAIM_USER_ID)
        ?.asLong()
        ?: throw ApiException.Unauthorized()

