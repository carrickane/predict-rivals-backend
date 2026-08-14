package com.predictrivals.footballApi

import java.time.LocalDate
import java.time.OffsetDateTime

data class DateRange(val from: LocalDate, val to: LocalDate)

data class FixtureDto(
    val externalMatchId: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffAt: OffsetDateTime,
)

/** providerStatus is the raw upstream status code (e.g. "1H", "HT", "2H", "FT", "AET", "PEN") — see the
 *  design's "status granularity note": callers decide whether to keep polling or freeze the match. */
data class LiveScoreDto(
    val externalMatchId: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val providerStatus: String,
)

/** Adapter over whichever football data provider is configured, so the provider is swappable without touching business logic. */
interface FootballDataProvider {
    suspend fun searchUpcomingFixtures(league: String?, dateRange: DateRange): List<FixtureDto>
    suspend fun getLiveScores(matchIds: List<String>): List<LiveScoreDto>
}

/** Provider status codes considered "normal match time" — see design doc section 7's polling window rule. */
object NormalTimeStatuses {
    val CODES = setOf("1H", "HT", "2H")
    fun isNormalTime(code: String) = code in CODES
}
