package com.predictrivals.plugins

import com.predictrivals.common.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

fun Application.configureCors(config: AppConfig) {
    install(CORS) {
        config.allowedOrigins.forEach { origin ->
            val withoutScheme = origin.substringAfter("://")
            val scheme = origin.substringBefore("://")
            allowHost(withoutScheme, schemes = listOf(scheme))
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = true
    }
}
