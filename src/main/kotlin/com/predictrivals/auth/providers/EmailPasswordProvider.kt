package com.predictrivals.auth.providers

import com.predictrivals.auth.AuthCredentials
import com.predictrivals.auth.AuthProvider
import com.predictrivals.auth.AuthProviderType
import com.predictrivals.auth.AuthRepository
import com.predictrivals.auth.AuthenticatedUser
import com.predictrivals.common.ApiException
import com.predictrivals.common.PasswordHasher

class EmailPasswordProvider(private val authRepository: AuthRepository) : AuthProvider {
    override val type = AuthProviderType.email

    override suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser = when (credentials) {
        is AuthCredentials.EmailRegister -> register(credentials)
        is AuthCredentials.EmailLogin -> login(credentials)
        else -> throw ApiException.BadRequest("EmailPasswordProvider cannot handle ${credentials::class.simpleName}")
    }

    private suspend fun register(credentials: AuthCredentials.EmailRegister): AuthenticatedUser {
        if (authRepository.findUserByEmail(credentials.email) != null) {
            throw ApiException.Conflict("An account with this email already exists")
        }
        val hash = PasswordHasher.hash(credentials.password)
        val user = authRepository.createUserWithPassword(credentials.name, credentials.email, hash)
        return AuthenticatedUser(user.id, user.name, user.role)
    }

    private suspend fun login(credentials: AuthCredentials.EmailLogin): AuthenticatedUser {
        // Generic failure message regardless of which check fails, to avoid account enumeration.
        val invalidCredentials = ApiException.Unauthorized("Invalid email or password")
        val (userId, hash) = authRepository.findPasswordHash(credentials.email) ?: throw invalidCredentials
        if (!PasswordHasher.verify(credentials.password, hash)) throw invalidCredentials
        val user = authRepository.findUserById(userId) ?: throw invalidCredentials
        return AuthenticatedUser(user.id, user.name, user.role)
    }
}
