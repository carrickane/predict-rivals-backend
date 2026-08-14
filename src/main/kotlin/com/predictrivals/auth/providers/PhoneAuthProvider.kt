package com.predictrivals.auth.providers

import com.predictrivals.auth.AuthCredentials
import com.predictrivals.auth.AuthProvider
import com.predictrivals.auth.AuthProviderType
import com.predictrivals.auth.AuthRepository
import com.predictrivals.auth.AuthenticatedUser
import com.predictrivals.common.ApiException
import com.predictrivals.common.Hashing
import java.time.OffsetDateTime

private const val OTP_TTL_MINUTES = 5L

/**
 * Phone/SMS auth: the OTP is generated, hashed, and expired by us (see AuthRepository /
 * game.phone_verification_codes); Twilio is only used to deliver the SMS text. Request-rate
 * and verify-attempt limits are enforced by the auth-sms-request / auth-sms-verify Ktor
 * rate limiters plus the per-code attempt cap in AuthRepository.verifyAndConsumePhoneCode.
 */
class PhoneAuthProvider(
    private val authRepository: AuthRepository,
    private val smsSender: SmsSender,
) : AuthProvider {
    override val type = AuthProviderType.phone

    suspend fun requestCode(phone: String) {
        val code = Hashing.randomNumericCode()
        authRepository.storePhoneCode(phone, Hashing.sha256Hex(code), OffsetDateTime.now().plusMinutes(OTP_TTL_MINUTES))
        smsSender.send(phone, "Your Predict Rivals verification code is $code. It expires in $OTP_TTL_MINUTES minutes.")
    }

    override suspend fun authenticate(credentials: AuthCredentials): AuthenticatedUser {
        val verify = credentials as? AuthCredentials.PhoneVerify
            ?: throw ApiException.BadRequest("PhoneAuthProvider requires a PhoneVerify credential")

        val valid = authRepository.verifyAndConsumePhoneCode(verify.phone, Hashing.sha256Hex(verify.code))
        if (!valid) throw ApiException.Unauthorized("Invalid or expired verification code")

        val user = authRepository.findOrCreateUserForIdentity(
            provider = AuthProviderType.phone,
            providerUserId = verify.phone,
            name = null,
            email = null,
            phone = verify.phone,
        )
        return AuthenticatedUser(user.id, user.name, user.role)
    }
}
