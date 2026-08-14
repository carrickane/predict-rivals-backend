package com.predictrivals.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Hashing {
    private val secureRandom = SecureRandom()

    /** Hashes opaque tokens/OTP codes for storage; these are never looked up by prefix, so SHA-256 is sufficient. */
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun randomToken(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
