package com.predictrivals.auth

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

enum class UserRole {
    player,
    admin,
}

enum class AuthProviderType {
    email,
    google,
    facebook,
}

object UsersTable : Table("game.users") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 128)
    val email = varchar("email", 256).nullable().uniqueIndex()
    val avatarUrl = varchar("avatar_url", 512).nullable()
    val role = varchar("role", 16)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object AuthIdentitiesTable : Table("game.auth_identities") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val provider = varchar("provider", 16)
    val providerUserId = varchar("provider_user_id", 256)
    val passwordHash = varchar("password_hash", 256).nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, providerUserId)
    }
}

object RefreshTokensTable : Table("game.refresh_tokens") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 256).uniqueIndex()
    val expiresAt = timestampWithTimeZone("expires_at")
    val revoked = bool("revoked")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
