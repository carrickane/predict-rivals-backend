package com.predictrivals.auth.providers

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import java.util.Base64

interface SmsSender {
    suspend fun send(toPhone: String, message: String)
}

class TwilioSmsSender(
    private val httpClient: HttpClient,
    private val accountSid: String,
    private val authToken: String,
    private val fromNumber: String,
) : SmsSender {
    override suspend fun send(toPhone: String, message: String) {
        val credentials = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())
        httpClient.submitForm(
            url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json",
            formParameters = Parameters.build {
                append("To", toPhone)
                append("From", fromNumber)
                append("Body", message)
            },
        ) {
            header(HttpHeaders.Authorization, "Basic $credentials")
        }
    }
}
