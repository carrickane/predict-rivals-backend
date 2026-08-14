package com.predictrivals.common

data class AppConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtAccessTokenTtlMinutes: Long,
    val jwtRefreshTokenTtlDays: Long,
    val allowedOrigins: List<String>,
    val apiFootballKey: String,
    val apiFootballBaseUrl: String,
    val googleClientId: String,
    val facebookAppId: String,
    val facebookAppSecret: String,
) {
    companion object {
        fun fromEnv(): AppConfig {
            fun env(name: String, default: String? = null): String =
                System.getenv(name) ?: default
                    ?: error("Missing required environment variable: $name")

            return AppConfig(
                databaseUrl = env("DATABASE_URL"),
                databaseUser = env("DATABASE_USER"),
                databasePassword = env("DATABASE_PASSWORD"),
                jwtSecret = env("JWT_SECRET"),
                jwtIssuer = env("JWT_ISSUER", "predict-rivals"),
                jwtAudience = env("JWT_AUDIENCE", "predict-rivals-clients"),
                jwtAccessTokenTtlMinutes = env("JWT_ACCESS_TOKEN_TTL_MINUTES", "30").toLong(),
                jwtRefreshTokenTtlDays = env("JWT_REFRESH_TOKEN_TTL_DAYS", "30").toLong(),
                allowedOrigins = env("ALLOWED_ORIGINS", "http://localhost:3000")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                apiFootballKey = env("API_FOOTBALL_KEY", ""),
                apiFootballBaseUrl = env("API_FOOTBALL_BASE_URL", "https://v3.football.api-sports.io"),
                googleClientId = env("GOOGLE_CLIENT_ID", ""),
                facebookAppId = env("FACEBOOK_APP_ID", ""),
                facebookAppSecret = env("FACEBOOK_APP_SECRET", ""),
            )
        }
    }
}
