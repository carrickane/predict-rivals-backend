package com.predictrivals.footballApi

import kotlinx.serialization.Serializable

@Serializable
data class ApiFootballFixturesResponse(val response: List<ApiFootballFixture>)

@Serializable
data class ApiFootballFixture(
    val fixture: ApiFootballFixtureInfo,
    val league: ApiFootballLeague,
    val teams: ApiFootballTeams,
    val goals: ApiFootballGoals,
)

@Serializable
data class ApiFootballFixtureInfo(val id: Long, val date: String, val status: ApiFootballStatus)

@Serializable
data class ApiFootballStatus(val short: String, val long: String)

@Serializable
data class ApiFootballLeague(val name: String)

@Serializable
data class ApiFootballTeams(val home: ApiFootballTeam, val away: ApiFootballTeam)

@Serializable
data class ApiFootballTeam(val name: String)

@Serializable
data class ApiFootballGoals(val home: Int? = null, val away: Int? = null)
