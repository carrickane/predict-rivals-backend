package com.predictrivals.auth

import com.predictrivals.common.ApiException
import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class UserRecord(val id: Long, val name: String, val email: String?, val phone: String?, val role: String)

class AuthRepository {

    suspend fun findUserById(userId: Long): UserRecord? = dbQuery {
        UsersTable.selectAll().where { UsersTable.id eq userId }
            .map { it.toUserRecord() }
            .singleOrNull()
    }

    suspend fun findUserByEmail(email: String): UserRecord? = dbQuery {
        UsersTable.selectAll().where { UsersTable.email eq email }
            .map { it.toUserRecord() }
            .singleOrNull()
    }

    suspend fun findIdentity(provider: AuthProviderType, providerUserId: String): Long? = dbQuery {
        AuthIdentitiesTable
            .selectAll().where { (AuthIdentitiesTable.provider eq provider.name) and (AuthIdentitiesTable.providerUserId eq providerUserId) }
            .map { it[AuthIdentitiesTable.userId] }
            .singleOrNull()
    }

    suspend fun findPasswordHash(email: String): Pair<Long, String>? = dbQuery {
        (UsersTable innerJoin AuthIdentitiesTable)
            .selectAll().where { (UsersTable.email eq email) and (AuthIdentitiesTable.provider eq AuthProviderType.email.name) }
            .mapNotNull { row ->
                val hash = row[AuthIdentitiesTable.passwordHash] ?: return@mapNotNull null
                row[UsersTable.id] to hash
            }
            .singleOrNull()
    }

    suspend fun createUserWithPassword(name: String, email: String, passwordHash: String): UserRecord = dbQuery {
        val now = OffsetDateTime.now()
        val userId = UsersTable.insert {
            it[UsersTable.name] = name
            it[UsersTable.email] = email
            it[UsersTable.role] = UserRole.player.name
            it[UsersTable.createdAt] = now
        } get UsersTable.id

        AuthIdentitiesTable.insert {
            it[AuthIdentitiesTable.userId] = userId
            it[AuthIdentitiesTable.provider] = AuthProviderType.email.name
            it[AuthIdentitiesTable.providerUserId] = email
            it[AuthIdentitiesTable.passwordHash] = passwordHash
            it[AuthIdentitiesTable.createdAt] = now
        }

        UserRecord(userId, name, email, null, UserRole.player.name)
    }

    suspend fun findOrCreateUserForIdentity(
        provider: AuthProviderType,
        providerUserId: String,
        name: String?,
        email: String?,
        phone: String?,
    ): UserRecord = dbQuery {
        val existingUserId = AuthIdentitiesTable
            .selectAll().where { (AuthIdentitiesTable.provider eq provider.name) and (AuthIdentitiesTable.providerUserId eq providerUserId) }
            .map { it[AuthIdentitiesTable.userId] }
            .singleOrNull()

        if (existingUserId != null) {
            return@dbQuery UsersTable.selectAll().where { UsersTable.id eq existingUserId }
                .map { it.toUserRecord() }
                .single()
        }

        val now = OffsetDateTime.now()
        val userId = UsersTable.insert {
            it[UsersTable.name] = name ?: providerUserId
            it[UsersTable.email] = email
            it[UsersTable.phone] = phone
            it[UsersTable.role] = UserRole.player.name
            it[UsersTable.createdAt] = now
        } get UsersTable.id

        AuthIdentitiesTable.insert {
            it[AuthIdentitiesTable.userId] = userId
            it[AuthIdentitiesTable.provider] = provider.name
            it[AuthIdentitiesTable.providerUserId] = providerUserId
            it[AuthIdentitiesTable.createdAt] = now
        }

        UserRecord(userId, name ?: providerUserId, email, phone, UserRole.player.name)
    }

    suspend fun storeRefreshToken(userId: Long, tokenHash: String, expiresAt: OffsetDateTime) = dbQuery {
        RefreshTokensTable.insert {
            it[RefreshTokensTable.userId] = userId
            it[RefreshTokensTable.tokenHash] = tokenHash
            it[RefreshTokensTable.expiresAt] = expiresAt
            it[RefreshTokensTable.revoked] = false
            it[RefreshTokensTable.createdAt] = OffsetDateTime.now()
        }
        Unit
    }

    suspend fun consumeRefreshToken(tokenHash: String): Long = dbQuery {
        val row = RefreshTokensTable
            .selectAll().where { RefreshTokensTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?: throw ApiException.Unauthorized("Invalid refresh token")

        if (row[RefreshTokensTable.revoked] || row[RefreshTokensTable.expiresAt].isBefore(OffsetDateTime.now())) {
            throw ApiException.Unauthorized("Refresh token expired or revoked")
        }

        RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq tokenHash }) {
            it[revoked] = true
        }

        row[RefreshTokensTable.userId]
    }

    suspend fun storePhoneCode(phone: String, codeHash: String, expiresAt: OffsetDateTime) = dbQuery {
        PhoneVerificationCodesTable.insert {
            it[PhoneVerificationCodesTable.phone] = phone
            it[PhoneVerificationCodesTable.codeHash] = codeHash
            it[PhoneVerificationCodesTable.expiresAt] = expiresAt
            it[PhoneVerificationCodesTable.attempts] = 0
            it[PhoneVerificationCodesTable.consumed] = false
            it[PhoneVerificationCodesTable.createdAt] = OffsetDateTime.now()
        }
        Unit
    }

    suspend fun verifyAndConsumePhoneCode(phone: String, codeHash: String): Boolean = dbQuery {
        val row = PhoneVerificationCodesTable
            .selectAll().where { (PhoneVerificationCodesTable.phone eq phone) and (PhoneVerificationCodesTable.consumed eq false) }
            .orderBy(PhoneVerificationCodesTable.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .firstOrNull()
            ?: return@dbQuery false

        if (row[PhoneVerificationCodesTable.expiresAt].isBefore(OffsetDateTime.now())) return@dbQuery false
        if (row[PhoneVerificationCodesTable.attempts] >= 5) return@dbQuery false

        PhoneVerificationCodesTable.update({ PhoneVerificationCodesTable.id eq row[PhoneVerificationCodesTable.id] }) {
            it[attempts] = row[PhoneVerificationCodesTable.attempts] + 1
        }

        if (row[PhoneVerificationCodesTable.codeHash] != codeHash) return@dbQuery false

        PhoneVerificationCodesTable.update({ PhoneVerificationCodesTable.id eq row[PhoneVerificationCodesTable.id] }) {
            it[consumed] = true
        }
        true
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toUserRecord() = UserRecord(
        id = this[UsersTable.id],
        name = this[UsersTable.name],
        email = this[UsersTable.email],
        phone = this[UsersTable.phone],
        role = this[UsersTable.role],
    )
}
