package com.predictrivals.common

import at.favre.lib.crypto.bcrypt.BCrypt

object PasswordHasher {
    private const val COST_FACTOR = 12

    fun hash(rawPassword: String): String =
        BCrypt.withDefaults().hashToString(COST_FACTOR, rawPassword.toCharArray())

    fun verify(rawPassword: String, hashed: String): Boolean =
        BCrypt.verifyer().verify(rawPassword.toCharArray(), hashed).verified
}
