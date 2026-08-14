package com.predictrivals.footballApi

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import java.time.OffsetDateTime

class ApiFootballProvider(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val budgetTracker: RequestBudgetTracker,
) : FootballDataProvider {

    override suspend fun searchUpcomingFixtures(league: String?, dateRange: DateRange): List<FixtureDto> {
        budgetTracker.tryConsume()
        val response = httpClient.get("$baseUrl/fixtures") {
            header("x-apisports-key", apiKey)
            parameter("from", dateRange.from.toString())
            parameter("to", dateRange.to.toString())
            league?.let { parameter("league", it) }
        }.body<ApiFootballFixturesResponse>()

        return response.response.map { it.toFixtureDto() }
    }

    override suspend fun getLiveScores(matchIds: List<String>): List<LiveScoreDto> {
        if (matchIds.isEmpty()) return emptyList()
        // One batched call for every currently-live match, never one call per match — this is
        // what keeps a full round's live window within the 100/day free-tier cap.
        if (!budgetTracker.tryConsume()) return emptyList()

        val response = httpClient.get("$baseUrl/fixtures") {
            header("x-apisports-key", apiKey)
            parameter("ids", matchIds.joinToString("-"))
        }.body<ApiFootballFixturesResponse>()

        return response.response.map { it.toLiveScoreDto() }
    }

    private fun ApiFootballFixture.toFixtureDto() = FixtureDto(
        externalMatchId = fixture.id.toString(),
        league = league.name,
        homeTeam = teams.home.name,
        awayTeam = teams.away.name,
        kickoffAt = OffsetDateTime.parse(fixture.date),
    )

    private fun ApiFootballFixture.toLiveScoreDto() = LiveScoreDto(
        externalMatchId = fixture.id.toString(),
        homeScore = goals.home,
        awayScore = goals.away,
        providerStatus = fixture.status.short,
    )
}
