# Round-Robin Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement real head-to-head `round_robin` tournaments end-to-end on the backend —
fixture schedule generation, matchday scoring via the existing goals-conversion mechanic, league
table standings with tie-breaks, and the API surface the client will consume.

**Architecture:** New `roundrobin` package holding the schedule algorithm, pairings
repository, and standings repository. Existing `ScoringService`/`StandingsRoutes`/
`LiveStateService` gain a format branch. The old dormant `game.standings` table/code (built for
a flat model that never fit real pairing) is removed.

**Tech Stack:** Ktor, Exposed, PostgreSQL/Flyway, Kotest (existing test framework).

**Reference:** [2026-08-18-round-robin-format-design.md](../specs/2026-08-18-round-robin-format-design.md)

**Hard constraints for this session (project CLAUDE.md):** no `git` commands, no
`./gradlew`/compiling. Every task creates/edits files only. "Run tests" steps are written for
whoever executes them later (once compiling is allowed) — do not run them now.

**Client-side work (dropdown, league-table UI, opponent indicator) is a separate follow-up plan**,
written after this one is reviewed — this plan is backend-only and produces a fully working API
on its own (verifiable via `curl` once builds are allowed).

---

### Task 1: Migration — schema for pairings and round-robin standings

**Files:**
- Create: `src/main/resources/db/migration/V9__round_robin_pairings_and_standings.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Round-robin format: real head-to-head pairing schedule + league-table standings, replacing
-- the flat individual-accumulation model game.standings was built for (never used by any format
-- in production, and doesn't fit real pairing) — see
-- docs/superpowers/specs/2026-08-18-round-robin-format-design.md.

DROP TABLE game.standings;

CREATE TABLE game.tournament_pairings (
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    round_number INT NOT NULL,
    player_a_user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    player_b_user_id BIGINT REFERENCES game.users (id) ON DELETE CASCADE,
    PRIMARY KEY (tournament_id, round_number, player_a_user_id)
);

CREATE INDEX idx_tournament_pairings_tournament_round ON game.tournament_pairings (tournament_id, round_number);

-- One row per player per round: for a real pairing (A, B), both (A, B) and (B, A) are stored so
-- "who is my opponent this round" is a single-row lookup by player_a_user_id. player_b_user_id
-- IS NULL marks a bye/BOT round for player_a (no reverse row in that case).
COMMENT ON TABLE game.tournament_pairings IS 'One row per player per round_number; symmetric pair rows for real matchups, single row with NULL player_b for a bye.';

CREATE TABLE game.round_robin_standings (
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    league_points INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    draws INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    goals_for INT NOT NULL DEFAULT 0,
    goals_against INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_id, user_id)
);

CREATE INDEX idx_round_robin_standings_tournament_id ON game.round_robin_standings (tournament_id);
```

- [ ] **Step 2: No migration run yet** — Flyway applies this automatically the next time the app
starts against the database; that's deferred along with all compiling/running in this session.

---

### Task 2: Exposed table objects, remove the old dead standings table

**Files:**
- Create: `src/main/kotlin/com/predictrivals/roundrobin/TournamentPairingsTable.kt`
- Create: `src/main/kotlin/com/predictrivals/roundrobin/RoundRobinStandingsTable.kt`
- Delete: `src/main/kotlin/com/predictrivals/standings/StandingsTable.kt`

- [ ] **Step 1: Create `TournamentPairingsTable.kt`**

```kotlin
package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table

object TournamentPairingsTable : Table("game.tournament_pairings") {
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val roundNumber = integer("round_number")
    val playerAUserId = long("player_a_user_id").references(UsersTable.id)
    val playerBUserId = long("player_b_user_id").references(UsersTable.id).nullable()

    override val primaryKey = PrimaryKey(tournamentId, roundNumber, playerAUserId)
}
```

- [ ] **Step 2: Create `RoundRobinStandingsTable.kt`**

```kotlin
package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.tournament.TournamentsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object RoundRobinStandingsTable : Table("game.round_robin_standings") {
    val tournamentId = long("tournament_id").references(TournamentsTable.id)
    val userId = long("user_id").references(UsersTable.id)
    val leaguePoints = integer("league_points")
    val wins = integer("wins")
    val draws = integer("draws")
    val losses = integer("losses")
    val goalsFor = integer("goals_for")
    val goalsAgainst = integer("goals_against")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(tournamentId, userId)
}
```

- [ ] **Step 3: Delete the old table object**

Delete `src/main/kotlin/com/predictrivals/standings/StandingsTable.kt` entirely — its table is
dropped in Task 1's migration, and nothing should reference it after Task 8.

---

### Task 3: Schedule generation algorithm (pure, testable)

**Files:**
- Create: `src/main/kotlin/com/predictrivals/roundrobin/RoundRobinScheduler.kt`
- Test: `src/test/kotlin/com/predictrivals/roundrobin/RoundRobinSchedulerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.predictrivals.roundrobin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class RoundRobinSchedulerTest : FunSpec({

    test("4 players, 2 legs: 6 matchdays, 2 pairs each, every pair plays twice, no byes") {
        val schedule = RoundRobinScheduler.generate(listOf(1L, 2L, 3L, 4L))
        schedule shouldHaveSize 6
        schedule.forEach { matchday ->
            matchday shouldHaveSize 2
            matchday.forEach { (_, b) -> (b == null) shouldBe false }
        }

        val ids = listOf(1L, 2L, 3L, 4L)
        val expectedPairs = ids.indices.flatMap { i -> (i + 1 until ids.size).map { j -> setOf(ids[i], ids[j]) } }
        val allPairsSeen = schedule.flatMap { matchday -> matchday.map { (a, b) -> setOf(a, b) } }
        expectedPairs.forEach { pair -> allPairsSeen.count { it == pair } shouldBe 2 }
    }

    test("5 players (odd), 2 legs: 10 matchdays, exactly one bye per round, 2 byes per player total") {
        val schedule = RoundRobinScheduler.generate(listOf(1L, 2L, 3L, 4L, 5L))
        schedule shouldHaveSize 10
        schedule.forEach { matchday ->
            matchday shouldHaveSize 3
            matchday.count { (_, b) -> b == null } shouldBe 1
        }

        val byeCounts = (1L..5L).associateWith { id ->
            schedule.count { matchday -> matchday.any { it.first == id && it.second == null } }
        }
        byeCounts.values.forEach { it shouldBe 2 }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Deferred — compiling/running is disabled this session. Whoever runs this: `./gradlew test
--tests "com.predictrivals.roundrobin.RoundRobinSchedulerTest"`, expected FAIL with "unresolved
reference: RoundRobinScheduler".

- [ ] **Step 3: Implement `RoundRobinScheduler.kt`**

```kotlin
package com.predictrivals.roundrobin

/**
 * Standard round-robin circle method, doubled (2 legs — every pair plays twice). Returns one
 * entry per matchday, in order (index 0 = round_number 1, ...); each matchday is a list of
 * (playerA, playerB) pairs. playerB null marks a bye for playerA that matchday (only possible
 * when the player count is odd).
 */
object RoundRobinScheduler {

    fun generate(playerIds: List<Long>): List<List<Pair<Long, Long?>>> {
        require(playerIds.size >= 2) { "Round-robin needs at least 2 players" }
        val singleLeg = generateSingleLeg(playerIds)
        return singleLeg + singleLeg
    }

    private fun generateSingleLeg(playerIds: List<Long>): List<List<Pair<Long, Long?>>> {
        val slots: MutableList<Long?> = playerIds.toMutableList()
        if (slots.size % 2 != 0) slots.add(null)
        val n = slots.size
        val rounds = n - 1
        val half = n / 2

        val result = mutableListOf<List<Pair<Long, Long?>>>()
        repeat(rounds) {
            val roundPairs = mutableListOf<Pair<Long, Long?>>()
            for (i in 0 until half) {
                val a = slots[i]
                val b = slots[n - 1 - i]
                when {
                    a != null && b != null -> roundPairs += (a to b)
                    a != null -> roundPairs += (a to null)
                    b != null -> roundPairs += (b to null)
                }
            }
            result += roundPairs

            // Rotate all but the first slot one position clockwise.
            val fixed = slots[0]
            val rotating = slots.subList(1, n)
            val last = rotating.removeAt(rotating.size - 1)
            rotating.add(0, last)
            slots[0] = fixed
            for (i in 1 until n) slots[i] = rotating[i - 1]
        }
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Deferred — same command as Step 2, expected PASS once compiling is allowed.

---

### Task 4: Pairings repository — schedule generation + queries

**Files:**
- Create: `src/main/kotlin/com/predictrivals/roundrobin/TournamentPairingsRepository.kt`

- [ ] **Step 1: Implement the repository**

```kotlin
package com.predictrivals.roundrobin

import com.predictrivals.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

data class PairingRecord(val tournamentId: Long, val roundNumber: Int, val playerAUserId: Long, val playerBUserId: Long?)

class TournamentPairingsRepository {

    suspend fun hasSchedule(tournamentId: Long): Boolean = dbQuery {
        TournamentPairingsTable.selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }.limit(1).any()
    }

    /** Idempotent: no-op if a schedule already exists for this tournament. */
    suspend fun ensureScheduleGenerated(tournamentId: Long, memberUserIds: List<Long>) {
        if (hasSchedule(tournamentId)) return
        val matchdays = RoundRobinScheduler.generate(memberUserIds)
        dbQuery {
            matchdays.forEachIndexed { index, pairs ->
                val roundNumber = index + 1
                pairs.forEach { (a, b) ->
                    TournamentPairingsTable.insert {
                        it[TournamentPairingsTable.tournamentId] = tournamentId
                        it[TournamentPairingsTable.roundNumber] = roundNumber
                        it[playerAUserId] = a
                        it[playerBUserId] = b
                    }
                    if (b != null) {
                        TournamentPairingsTable.insert {
                            it[TournamentPairingsTable.tournamentId] = tournamentId
                            it[TournamentPairingsTable.roundNumber] = roundNumber
                            it[playerAUserId] = b
                            it[playerBUserId] = a
                        }
                    }
                }
            }
        }
    }

    suspend fun listForRound(tournamentId: Long, roundNumber: Int): List<PairingRecord> = dbQuery {
        TournamentPairingsTable
            .selectAll().where { (TournamentPairingsTable.tournamentId eq tournamentId) and (TournamentPairingsTable.roundNumber eq roundNumber) }
            .map { it.toRecord() }
    }

    suspend fun listAllForTournament(tournamentId: Long): List<PairingRecord> = dbQuery {
        TournamentPairingsTable
            .selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }
            .orderBy(TournamentPairingsTable.roundNumber to SortOrder.ASC)
            .map { it.toRecord() }
    }

    suspend fun maxRoundNumber(tournamentId: Long): Int? = dbQuery {
        TournamentPairingsTable
            .selectAll().where { TournamentPairingsTable.tournamentId eq tournamentId }
            .maxOfOrNull { it[TournamentPairingsTable.roundNumber] }
    }

    /** Head-to-head lookup for standings tie-breaking: which round_numbers this pair played. */
    suspend fun headToHeadRounds(tournamentId: Long, userIdA: Long, userIdB: Long): List<Int> = dbQuery {
        TournamentPairingsTable
            .selectAll().where {
                (TournamentPairingsTable.tournamentId eq tournamentId) and
                    (TournamentPairingsTable.playerAUserId eq userIdA) and
                    (TournamentPairingsTable.playerBUserId eq userIdB)
            }
            .map { it[TournamentPairingsTable.roundNumber] }
    }

    private fun ResultRow.toRecord() = PairingRecord(
        tournamentId = this[TournamentPairingsTable.tournamentId],
        roundNumber = this[TournamentPairingsTable.roundNumber],
        playerAUserId = this[TournamentPairingsTable.playerAUserId],
        playerBUserId = this[TournamentPairingsTable.playerBUserId],
    )
}
```

---

### Task 5: Round-robin standings repository — scoring deltas, ranking, tie-breaks

**Files:**
- Create: `src/main/kotlin/com/predictrivals/roundrobin/RoundRobinStandingsRepository.kt`

- [ ] **Step 1: Implement the repository**

```kotlin
package com.predictrivals.roundrobin

import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.scoring.RoundScoresRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class RoundRobinStandingRow(
    val userId: Long,
    val name: String,
    val leaguePoints: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
)

private data class MatchdayOutcome(val points: Int, val wins: Int, val draws: Int, val losses: Int)

private fun outcomeFor(myGoals: Int, opponentGoals: Int): MatchdayOutcome = when {
    myGoals > opponentGoals -> MatchdayOutcome(points = 3, wins = 1, draws = 0, losses = 0)
    myGoals < opponentGoals -> MatchdayOutcome(points = 0, wins = 0, draws = 0, losses = 1)
    else -> MatchdayOutcome(points = 1, wins = 0, draws = 1, losses = 0)
}

class RoundRobinStandingsRepository(
    private val pairingsRepository: TournamentPairingsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val roundsRepository: RoundsRepository,
) {

    /** Bye/BOT matchday: only the player's own goals-for tally moves, nothing else. */
    suspend fun applyByeRound(tournamentId: Long, userId: Long, goalsFor: Int) =
        upsertDelta(tournamentId, userId, pointsDelta = 0, winsDelta = 0, drawsDelta = 0, lossesDelta = 0, goalsForDelta = goalsFor, goalsAgainstDelta = 0)

    suspend fun applyMatchdayResult(tournamentId: Long, playerAUserId: Long, playerBUserId: Long, playerAGoals: Int, playerBGoals: Int) {
        val a = outcomeFor(playerAGoals, playerBGoals)
        val b = outcomeFor(playerBGoals, playerAGoals)
        upsertDelta(tournamentId, playerAUserId, a.points, a.wins, a.draws, a.losses, playerAGoals, playerBGoals)
        upsertDelta(tournamentId, playerBUserId, b.points, b.wins, b.draws, b.losses, playerBGoals, playerAGoals)
    }

    suspend fun getStandings(tournamentId: Long): List<RoundRobinStandingRow> {
        val names = dbQuery { UsersTable.selectAll().associate { it[UsersTable.id] to it[UsersTable.name] } }
        val rows = dbQuery {
            RoundRobinStandingsTable
                .selectAll().where { RoundRobinStandingsTable.tournamentId eq tournamentId }
                .map {
                    RoundRobinStandingRow(
                        userId = it[RoundRobinStandingsTable.userId],
                        name = names[it[RoundRobinStandingsTable.userId]] ?: "Unknown",
                        leaguePoints = it[RoundRobinStandingsTable.leaguePoints],
                        wins = it[RoundRobinStandingsTable.wins],
                        draws = it[RoundRobinStandingsTable.draws],
                        losses = it[RoundRobinStandingsTable.losses],
                        goalsFor = it[RoundRobinStandingsTable.goalsFor],
                        goalsAgainst = it[RoundRobinStandingsTable.goalsAgainst],
                    )
                }
        }
        return rankWithTiebreaks(tournamentId, rows)
    }

    /**
     * Ranks by league points -> goals scored -> wins -> goal difference, then breaks a remaining
     * adjacent 2-way tie by head-to-head result. This does NOT resolve a 3+-way tie via a full
     * mini-league recompute (rare in practice at this scale) — that case, and a head-to-head that's
     * itself still level (e.g. split results across both legs), falls back to the stable userId
     * order already applied by the primary sort, per the design doc.
     */
    private suspend fun rankWithTiebreaks(tournamentId: Long, rows: List<RoundRobinStandingRow>): List<RoundRobinStandingRow> {
        val sorted = rows.sortedWith(
            compareByDescending<RoundRobinStandingRow> { it.leaguePoints }
                .thenByDescending { it.goalsFor }
                .thenByDescending { it.wins }
                .thenByDescending { it.goalsFor - it.goalsAgainst }
                .thenBy { it.userId },
        ).toMutableList()

        var i = 0
        while (i < sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val tied = a.leaguePoints == b.leaguePoints && a.goalsFor == b.goalsFor &&
                a.wins == b.wins && (a.goalsFor - a.goalsAgainst) == (b.goalsFor - b.goalsAgainst)
            if (tied) {
                val winner = headToHeadWinnerUserId(tournamentId, a.userId, b.userId)
                if (winner == b.userId) {
                    sorted[i] = b
                    sorted[i + 1] = a
                }
            }
            i++
        }
        return sorted
    }

    private suspend fun headToHeadWinnerUserId(tournamentId: Long, userIdA: Long, userIdB: Long): Long? {
        val roundNumbers = pairingsRepository.headToHeadRounds(tournamentId, userIdA, userIdB)
        if (roundNumbers.isEmpty()) return null

        var aPoints = 0
        var bPoints = 0
        roundNumbers.forEach { roundNumber ->
            val round = roundsRepository.findByTournamentAndNumber(tournamentId, roundNumber) ?: return@forEach
            val aGoals = roundScoresRepository.find(userIdA, round.id)?.goalsAwarded ?: 0
            val bGoals = roundScoresRepository.find(userIdB, round.id)?.goalsAwarded ?: 0
            when {
                aGoals > bGoals -> aPoints += 3
                aGoals < bGoals -> bPoints += 3
                else -> { aPoints += 1; bPoints += 1 }
            }
        }
        return when {
            aPoints > bPoints -> userIdA
            bPoints > aPoints -> userIdB
            else -> null
        }
    }

    private suspend fun upsertDelta(
        tournamentId: Long,
        userId: Long,
        pointsDelta: Int,
        winsDelta: Int,
        drawsDelta: Int,
        lossesDelta: Int,
        goalsForDelta: Int,
        goalsAgainstDelta: Int,
    ) = dbQuery {
        val now = OffsetDateTime.now()
        val existing = RoundRobinStandingsTable
            .selectAll().where { (RoundRobinStandingsTable.tournamentId eq tournamentId) and (RoundRobinStandingsTable.userId eq userId) }
            .singleOrNull()

        if (existing != null) {
            RoundRobinStandingsTable.update({ (RoundRobinStandingsTable.tournamentId eq tournamentId) and (RoundRobinStandingsTable.userId eq userId) }) {
                it[leaguePoints] = existing[RoundRobinStandingsTable.leaguePoints] + pointsDelta
                it[wins] = existing[RoundRobinStandingsTable.wins] + winsDelta
                it[draws] = existing[RoundRobinStandingsTable.draws] + drawsDelta
                it[losses] = existing[RoundRobinStandingsTable.losses] + lossesDelta
                it[goalsFor] = existing[RoundRobinStandingsTable.goalsFor] + goalsForDelta
                it[goalsAgainst] = existing[RoundRobinStandingsTable.goalsAgainst] + goalsAgainstDelta
                it[updatedAt] = now
            }
        } else {
            RoundRobinStandingsTable.insert {
                it[RoundRobinStandingsTable.tournamentId] = tournamentId
                it[RoundRobinStandingsTable.userId] = userId
                it[leaguePoints] = pointsDelta
                it[wins] = winsDelta
                it[draws] = drawsDelta
                it[losses] = lossesDelta
                it[goalsFor] = goalsForDelta
                it[goalsAgainst] = goalsAgainstDelta
                it[updatedAt] = now
            }
        }
        Unit
    }
}
```

---

### Task 6: Tournament creation accepts format; schedule generated on start

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/tournament/TournamentModels.kt`
- Modify: `src/main/kotlin/com/predictrivals/tournament/TournamentRepository.kt`
- Modify: `src/main/kotlin/com/predictrivals/tournament/TournamentRoutes.kt`

- [ ] **Step 1: `TournamentModels.kt` — add `format` to the create request**

Change:
```kotlin
@Serializable
data class CreateTournamentRequest(val name: String, val playerLimit: Int)
```
to:
```kotlin
@Serializable
data class CreateTournamentRequest(val name: String, val playerLimit: Int, val format: String = "round_robin")
```

- [ ] **Step 2: `TournamentRepository.kt` — accept format on create, add member-listing**

Change the `create` signature and body:
```kotlin
    suspend fun create(name: String, ownerUserId: Long, playerLimit: Int, format: String): TournamentRecord = dbQuery {
        val now = OffsetDateTime.now()
        val joinCode = generateUniqueJoinCodeBlocking()

        val tournamentId = TournamentsTable.insert {
            it[TournamentsTable.name] = name
            it[TournamentsTable.ownerUserId] = ownerUserId
            it[TournamentsTable.joinCode] = joinCode
            it[TournamentsTable.playerLimit] = playerLimit
            it[TournamentsTable.format] = format
            it[status] = TournamentStatus.open.name
            it[createdAt] = now
        } get TournamentsTable.id
```
(only the `it[format] = TournamentFormat.solo_points.name` line changes, to
`it[TournamentsTable.format] = format`; the rest of the function is unchanged.)

Add a new method (anywhere in the class):
```kotlin
    suspend fun listMemberUserIds(tournamentId: Long): List<Long> = dbQuery {
        TournamentMembershipsTable.selectAll().where { TournamentMembershipsTable.tournamentId eq tournamentId }
            .map { it[TournamentMembershipsTable.userId] }
    }
```

- [ ] **Step 3: `TournamentRoutes.kt` — validate format, wire schedule generation**

Change the `tournamentRoutes` signature to take the new repository, and the create/join/start
handlers:

```kotlin
fun Route.tournamentRoutes(tournamentRepository: TournamentRepository, pairingsRepository: TournamentPairingsRepository) {
    route("/api/tournaments") {
        authenticate(AUTH_JWT) {
            post {
                val userId = call.principalUserId()
                val body = call.receive<CreateTournamentRequest>()
                if (body.name.isBlank()) throw ApiException.BadRequest("Tournament name is required")
                if (body.playerLimit !in MIN_PLAYER_LIMIT..MAX_PLAYER_LIMIT) {
                    throw ApiException.BadRequest("Player limit must be between $MIN_PLAYER_LIMIT and $MAX_PLAYER_LIMIT")
                }
                if (body.format !in setOf(TournamentFormat.solo_points.name, TournamentFormat.round_robin.name)) {
                    throw ApiException.BadRequest("format must be one of: solo_points, round_robin")
                }
                val tournament = tournamentRepository.create(body.name, userId, body.playerLimit, body.format)
                call.respond(HttpStatusCode.Created, tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            post("/join") {
                val userId = call.principalUserId()
                val body = call.receive<JoinTournamentRequest>()
                val tournament = tournamentRepository.join(userId, body.joinCode.trim().uppercase())
                if (tournament.status == TournamentStatus.active.name && tournament.format == TournamentFormat.round_robin.name) {
                    pairingsRepository.ensureScheduleGenerated(tournament.id, tournamentRepository.listMemberUserIds(tournament.id))
                }
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            post("/{id}/start") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["id"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.startNow(tournamentId, userId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    pairingsRepository.ensureScheduleGenerated(tournament.id, tournamentRepository.listMemberUserIds(tournament.id))
                }
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }

            get("/mine") {
                val userId = call.principalUserId()
                val tournaments = tournamentRepository.listForUser(userId)
                call.respond(tournaments.map { it.toResponse(tournamentRepository.memberCount(it.id)) })
            }

            get("/{id}") {
                val tournamentId = call.parameters["id"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.findById(tournamentId)
                call.respond(tournament.toResponse(tournamentRepository.memberCount(tournament.id)))
            }
        }
    }
}
```

Add the two new imports at the top of the file:
```kotlin
import com.predictrivals.roundrobin.TournamentPairingsRepository
import com.predictrivals.tournament.TournamentFormat
```
(`TournamentFormat`/`TournamentStatus` already live in `com.predictrivals.tournament` — same
package as this file, so that second import isn't actually needed; only add the
`TournamentPairingsRepository` import.)

`ensureScheduleGenerated` being idempotent (Task 4) means calling it after both `join`
(auto-start) and `/start` (manual start) is safe even if only one of them actually causes the
`open -> active` transition for a given tournament.

---

### Task 7: Cap round creation at the generated schedule length

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/adminMatches/AdminMatchRoutes.kt`

- [ ] **Step 1: Add the cap check in `tournamentMatchRoutes`**

Change the `tournamentMatchRoutes` signature to accept the pairings repository, and add a check
right after the existing `body.matches.size` validation:

```kotlin
fun Route.tournamentMatchRoutes(
    adminMatchRepository: AdminMatchRepository,
    roundsRepository: RoundsRepository,
    tournamentRepository: TournamentRepository,
    pairingsRepository: TournamentPairingsRepository,
    scoringService: ScoringService,
    auditLogRepository: AdminAuditLogRepository,
    liveStateService: LiveStateService,
    liveHub: LiveHub,
) {
    route("/api/tournaments/{tournamentId}/matches") {
        authenticate(AUTH_JWT) {
            post {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                val tournament = tournamentRepository.findById(tournamentId)
                tournament.requireOwner(userId)
                tournament.requireActive()

                val body = call.receive<CreateRoundMatchesRequest>()
                if (body.matches.size != MATCHES_PER_ROUND) {
                    throw ApiException.BadRequest("A round must have exactly $MATCHES_PER_ROUND matches")
                }
                if (tournament.format == TournamentFormat.round_robin.name) {
                    val maxRound = pairingsRepository.maxRoundNumber(tournamentId)
                    if (maxRound != null && body.roundNumber > maxRound) {
                        throw ApiException.BadRequest("round_robin schedule only has $maxRound rounds for this tournament")
                    }
                }

                if (roundsRepository.findByTournamentAndNumber(tournamentId, body.roundNumber) == null) {
                    roundsRepository.createIfMissing(tournamentId, body.roundNumber)
                }

                val created = adminMatchRepository.createRoundMatches(tournamentId, body.roundNumber, body.matches)
                call.respond(HttpStatusCode.Created, created.map { it.toResponse() })
            }
```

(the rest of the file — `fixtureRoutes`, the `patch("/{matchId}/score")` handler, and the
`toResponse()` extension — is unchanged.)

Add the import:
```kotlin
import com.predictrivals.roundrobin.TournamentPairingsRepository
```

---

### Task 8: Scoring wiring — matchday results, remove dead solo-goals code

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/scoring/ScoringService.kt`
- Modify: `src/main/kotlin/com/predictrivals/standings/StandingsRepository.kt`

- [ ] **Step 1: `ScoringService.kt` — swap the dependency, wire round_robin matchday scoring**

Replace the class entirely:

```kotlin
package com.predictrivals.scoring

import com.predictrivals.adminMatches.AdminMatchRecord
import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.adminMatches.MatchStatus
import com.predictrivals.predictions.PredictionsRepository
import com.predictrivals.rounds.RoundRecord
import com.predictrivals.rounds.RoundStatus
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.roundrobin.TournamentPairingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentRepository

/**
 * Bridges the pure ScoringEngine to persistence: recalculates predictions when a match's score
 * changes, and keeps the round's lifecycle status (scheduled -> live -> finished) in sync for
 * every format — that status is what "current round" detection relies on. For `solo_points`
 * tournaments that's the whole job — no round_scores write, no standings write, since standings
 * are computed live from predictions directly (see StandingsRepository.getSoloStandings). For
 * `round_robin`, once a round finishes, each pair's frozen round-goals get compared to produce
 * that matchday's win/draw/loss (see RoundRobinStandingsRepository).
 */
class ScoringService(
    private val predictionsRepository: PredictionsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val roundsRepository: RoundsRepository,
    private val tournamentRepository: TournamentRepository,
    private val pairingsRepository: TournamentPairingsRepository,
    private val roundRobinStandingsRepository: RoundRobinStandingsRepository,
) {

    /** Called whenever a match's score changes — from a live poll update or a manual owner override. */
    suspend fun recalculateMatch(matchId: Long) {
        val match = adminMatchRepository.getOrThrow(matchId)
        val tournament = tournamentRepository.findById(match.tournamentId)
        val round = roundsRepository.findByTournamentAndNumber(tournament.id, match.roundNumber) ?: return
        val wasAlreadyFinished = round.status == RoundStatus.finished.name

        val homeScore = match.homeScore
        val awayScore = match.awayScore
        val affectedUserIds = mutableListOf<Long>()

        if (homeScore != null && awayScore != null) {
            val predictions = predictionsRepository.listByMatch(matchId).filter { !it.isLate }
            predictions.forEach { prediction ->
                val result = ScoringEngine.score(
                    predictedHome = prediction.predictedHomeScore,
                    predictedAway = prediction.predictedAwayScore,
                    actualHome = homeScore,
                    actualAway = awayScore,
                )
                predictionsRepository.updateScore(prediction.id, result.points, result.isExact)
            }
            affectedUserIds += predictions.map { it.userId }.distinct()
        }

        val roundMatches = adminMatchRepository.listByTournamentAndRound(round.tournamentId, round.roundNumber)
        updateRoundLifecycleStatus(round, roundMatches)

        if (tournament.format == TournamentFormat.solo_points.name) return

        affectedUserIds.forEach { userId -> recomputeRoundScore(round.id, userId) }

        val nowAllFinished = roundMatches.isNotEmpty() && roundMatches.all { it.status == MatchStatus.finished.name }
        if (!wasAlreadyFinished && nowAllFinished) {
            roundScoresRepository.freeze(round.id)
            applyRoundRobinMatchday(round.tournamentId, round.id, round.roundNumber)
        }
    }

    /** Keeps round.status in sync with its matches' progress, for every tournament format. */
    private suspend fun updateRoundLifecycleStatus(round: RoundRecord, roundMatches: List<AdminMatchRecord>) {
        if (round.status == RoundStatus.finished.name) return
        val allFinished = roundMatches.isNotEmpty() && roundMatches.all { it.status == MatchStatus.finished.name }
        val anyUnderway = roundMatches.any { it.status != MatchStatus.scheduled.name }

        when {
            allFinished -> roundsRepository.updateStatus(round.id, RoundStatus.finished)
            anyUnderway && round.status == RoundStatus.scheduled.name -> roundsRepository.updateStatus(round.id, RoundStatus.live)
        }
    }

    private suspend fun recomputeRoundScore(roundId: Long, userId: Long) {
        val predictions = predictionsRepository.listByRoundAndUser(roundId, userId).filter { !it.isLate }
        val pointsRaw = predictions.sumOf { it.pointsAwarded ?: 0 }
        val exactCount = predictions.count { it.isExact == true }
        val goals = ScoringEngine.convertToGoals(pointsRaw, exactCount)
        roundScoresRepository.upsert(userId, roundId, pointsRaw, exactCount, goals)
    }

    /** Compares each pairing's two frozen round-goals values into a win/draw/loss (or a bye's solo goals-for). */
    private suspend fun applyRoundRobinMatchday(tournamentId: Long, roundId: Long, roundNumber: Int) {
        val pairings = pairingsRepository.listForRound(tournamentId, roundNumber)
        val processed = mutableSetOf<Long>()
        pairings.forEach { pairing ->
            if (pairing.playerAUserId in processed) return@forEach
            val myGoals = roundScoresRepository.find(pairing.playerAUserId, roundId)?.goalsAwarded ?: 0
            val opponentId = pairing.playerBUserId
            if (opponentId == null) {
                roundRobinStandingsRepository.applyByeRound(tournamentId, pairing.playerAUserId, goalsFor = myGoals)
                processed += pairing.playerAUserId
                return@forEach
            }
            val opponentGoals = roundScoresRepository.find(opponentId, roundId)?.goalsAwarded ?: 0
            roundRobinStandingsRepository.applyMatchdayResult(tournamentId, pairing.playerAUserId, opponentId, myGoals, opponentGoals)
            processed += pairing.playerAUserId
            processed += opponentId
        }
    }
}
```

- [ ] **Step 2: `StandingsRepository.kt` — remove the dead round-robin-shaped code**

Delete these from the class: `applyFinishedRound`, `getStandings`, `getUserStanding`, and the
`StandingRow` data class at the top of the file. Keep everything else (`SoloStandingRow`,
`UserTournamentStats`, `getSoloStandings`, `getSoloRoundScores`, `fetchMemberNames`,
`aggregateSoloPoints`, `getUserSoloStats`) exactly as-is — those are solo_points-only and
unaffected by this work. Also remove the now-unused imports this leaves behind (`RoundsTable`,
`OffsetDateTime` — check whether `getUserSoloStats` or anything remaining still needs them
before removing; if unused, delete the import lines).

---

### Task 9: Standings API — branch by format, remove dead response models

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/standings/StandingsModels.kt`
- Modify: `src/main/kotlin/com/predictrivals/standings/StandingsRoutes.kt`

- [ ] **Step 1: `StandingsModels.kt` — remove the dead shape, add the round_robin shape**

Delete `StandingEntryResponse`, `List<StandingRow>.toRanked()`, `TopScorerEntryResponse`, and
`UserStatsResponse` (the non-solo ones near the top of the file — everything under the `//
solo_points format` comment stays). Add:

```kotlin
@Serializable
data class RoundRobinStandingEntryResponse(
    val rank: Int,
    val userId: Long,
    val name: String,
    val leaguePoints: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
)

fun List<RoundRobinStandingRow>.toRankedRoundRobin(): List<RoundRobinStandingEntryResponse> =
    mapIndexed { index, row ->
        RoundRobinStandingEntryResponse(
            rank = index + 1,
            userId = row.userId,
            name = row.name,
            leaguePoints = row.leaguePoints,
            wins = row.wins,
            draws = row.draws,
            losses = row.losses,
            goalsFor = row.goalsFor,
            goalsAgainst = row.goalsAgainst,
            goalDifference = row.goalsFor - row.goalsAgainst,
        )
    }

@Serializable
data class RoundRobinTopScorerEntryResponse(val rank: Int, val userId: Long, val name: String, val goalsFor: Int)

fun List<RoundRobinStandingRow>.toTopScorersRoundRobin(): List<RoundRobinTopScorerEntryResponse> =
    sortedByDescending { it.goalsFor }
        .mapIndexed { index, row -> RoundRobinTopScorerEntryResponse(index + 1, row.userId, row.name, row.goalsFor) }
```

Add the import: `import com.predictrivals.roundrobin.RoundRobinStandingRow`

- [ ] **Step 2: `StandingsRoutes.kt` — branch each endpoint by format**

Replace the whole file:

```kotlin
package com.predictrivals.standings

import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.standingsRoutes(
    standingsRepository: StandingsRepository,
    roundRobinStandingsRepository: RoundRobinStandingsRepository,
    tournamentRepository: TournamentRepository,
) {
    route("/api/tournaments/{tournamentId}") {
        authenticate(AUTH_JWT) {
            get("/standings") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its standings")
                }
                val tournament = tournamentRepository.findById(tournamentId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    call.respond(roundRobinStandingsRepository.getStandings(tournamentId).toRankedRoundRobin())
                } else {
                    call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
                }
            }

            get("/top-scorers") {
                val userId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(userId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its top scorers")
                }
                val tournament = tournamentRepository.findById(tournamentId)
                if (tournament.format == TournamentFormat.round_robin.name) {
                    call.respond(roundRobinStandingsRepository.getStandings(tournamentId).toTopScorersRoundRobin())
                } else {
                    // solo_points has no separate "goals" unit - top scorers is the same ranking as standings
                    call.respond(standingsRepository.getSoloStandings(tournamentId).toRankedSolo())
                }
            }

            get("/users/{userId}/stats") {
                val callerId = call.principalUserId()
                val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid tournament id")
                if (!tournamentRepository.isMember(callerId, tournamentId)) {
                    throw ApiException.Forbidden("Join the tournament to view its stats")
                }
                val targetUserId = call.parameters["userId"]?.toLongOrNull()
                    ?: throw ApiException.BadRequest("Invalid user id")
                // NOTE: per-user stats stay solo-shaped for both formats in this iteration — a
                // round_robin-specific stats shape (W/D/L for this one player) wasn't designed;
                // out of scope here, tracked as a known gap.
                call.respond(standingsRepository.getUserSoloStats(tournamentId, targetUserId).toResponse())
            }
        }
    }
}
```

---

### Task 10: Live payload — round_robin standings/round-scores

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/live/LiveModels.kt`
- Modify: `src/main/kotlin/com/predictrivals/live/LiveStateService.kt`

- [ ] **Step 1: `LiveModels.kt` — add nullable round_robin fields to the existing shapes**

Replace `LiveStandingEntry` and `LiveRoundScoreEntry`:

```kotlin
@Serializable
data class LiveStandingEntry(
    val rank: Int,
    val userId: Long,
    val name: String,
    val totalPoints: Int? = null,
    val exactCount: Int? = null,
    val leaguePoints: Int? = null,
    val wins: Int? = null,
    val draws: Int? = null,
    val losses: Int? = null,
    val goalsFor: Int? = null,
    val goalsAgainst: Int? = null,
)

@Serializable
data class LiveRoundScoreEntry(
    val userId: Long,
    val name: String,
    val roundPoints: Int,
)
```

(`LiveRoundScoreEntry.roundPoints` is reused to carry the in-progress round-goals value for
round_robin — see Step 2. `LiveMatchResponse` and `LiveStateResponse` are unchanged; opponent
info is deliberately **not** added here, since the live payload is one shared broadcast per
tournament, not per-viewer — the client derives "my opponent this round" itself from the
`/pairings` endpoint, which doesn't vary per viewer.)

- [ ] **Step 2: `LiveStateService.kt` — branch by format**

Replace the whole file:

```kotlin
package com.predictrivals.live

import com.predictrivals.adminMatches.AdminMatchRepository
import com.predictrivals.auth.UsersTable
import com.predictrivals.common.dbQuery
import com.predictrivals.rounds.RoundsRepository
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.scoring.RoundScoresRepository
import com.predictrivals.standings.StandingsRepository
import com.predictrivals.tournament.TournamentFormat
import com.predictrivals.tournament.TournamentMembershipsTable
import com.predictrivals.tournament.TournamentRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

/** Assembles the combined live payload (matches + standings + round-in-progress scores) shared by GET .../live and every WebSocket broadcast. */
class LiveStateService(
    private val roundsRepository: RoundsRepository,
    private val adminMatchRepository: AdminMatchRepository,
    private val standingsRepository: StandingsRepository,
    private val roundRobinStandingsRepository: RoundRobinStandingsRepository,
    private val roundScoresRepository: RoundScoresRepository,
    private val tournamentRepository: TournamentRepository,
) {
    suspend fun buildCurrentState(tournamentId: Long): LiveStateResponse {
        val round = roundsRepository.findCurrentRound(tournamentId)
        val matches = adminMatchRepository.listByTournamentAndRound(tournamentId, round.roundNumber)
        val tournament = tournamentRepository.findById(tournamentId)

        val matchResponses = matches.map {
            LiveMatchResponse(it.id, it.homeTeam, it.awayTeam, it.kickoffAt.toString(), it.status, it.homeScore, it.awayScore)
        }

        if (tournament.format == TournamentFormat.round_robin.name) {
            val standings = roundRobinStandingsRepository.getStandings(tournamentId)
                .mapIndexed { index, row ->
                    LiveStandingEntry(
                        rank = index + 1,
                        userId = row.userId,
                        name = row.name,
                        leaguePoints = row.leaguePoints,
                        wins = row.wins,
                        draws = row.draws,
                        losses = row.losses,
                        goalsFor = row.goalsFor,
                        goalsAgainst = row.goalsAgainst,
                    )
                }
            val roundScores = liveRoundGoals(tournamentId, round.id)
            return LiveStateResponse(matchResponses, standings, roundScores)
        }

        val standings = standingsRepository.getSoloStandings(tournamentId)
            .mapIndexed { index, row -> LiveStandingEntry(index + 1, row.userId, row.name, totalPoints = row.totalPoints, exactCount = row.exactCount) }
        val roundScores = standingsRepository.getSoloRoundScores(tournamentId, round.id)
            .map { LiveRoundScoreEntry(it.userId, it.name, it.totalPoints) }

        return LiveStateResponse(matchResponses, standings, roundScores)
    }

    /** In-progress (not-yet-frozen included) per-user round-goals for round_robin's live round-score breakdown. */
    private suspend fun liveRoundGoals(tournamentId: Long, roundId: Long): List<LiveRoundScoreEntry> {
        val names = dbQuery {
            (TournamentMembershipsTable innerJoin UsersTable)
                .selectAll().where { TournamentMembershipsTable.tournamentId eq tournamentId }
                .associate { it[UsersTable.id] to it[UsersTable.name] }
        }
        return roundScoresRepository.listByRound(roundId).map { record ->
            LiveRoundScoreEntry(record.userId, names[record.userId] ?: "Unknown", record.goalsAwarded)
        }
    }
}
```

`RoundScoresRepository` needs a `listByRound` method for this — check `RoundScoresRepository.kt`
(`src/main/kotlin/com/predictrivals/scoring/RoundScoresRepository.kt`) first: it already has
`listByRound(roundId: Long): List<RoundScoreRecord>` (confirmed present) — no change needed
there.

---

### Task 11: New endpoint — full pairing schedule

**Files:**
- Create: `src/main/kotlin/com/predictrivals/roundrobin/PairingsRoutes.kt`

- [ ] **Step 1: Implement the route**

```kotlin
package com.predictrivals.roundrobin

import com.predictrivals.common.ApiException
import com.predictrivals.plugins.AUTH_JWT
import com.predictrivals.plugins.principalUserId
import com.predictrivals.tournament.TournamentRepository
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class PairingResponse(val roundNumber: Int, val opponentUserId: Long?, val isBotMatch: Boolean)

fun Route.pairingsRoutes(pairingsRepository: TournamentPairingsRepository, tournamentRepository: TournamentRepository) {
    authenticate(AUTH_JWT) {
        get("/api/tournaments/{tournamentId}/pairings") {
            val userId = call.principalUserId()
            val tournamentId = call.parameters["tournamentId"]?.toLongOrNull()
                ?: throw ApiException.BadRequest("Invalid tournament id")
            if (!tournamentRepository.isMember(userId, tournamentId)) {
                throw ApiException.Forbidden("Join the tournament to view its schedule")
            }
            val mine = pairingsRepository.listAllForTournament(tournamentId).filter { it.playerAUserId == userId }
            call.respond(
                mine.map { PairingResponse(it.roundNumber, it.playerBUserId, isBotMatch = it.playerBUserId == null) },
            )
        }
    }
}
```

This returns the calling user's own schedule (their opponent each round_number) — the natural
shape for "show me my fixtures," and avoids leaking every other player's pairings in one payload.

---

### Task 12: Wire everything into `Application.kt`

**Files:**
- Modify: `src/main/kotlin/com/predictrivals/Application.kt`

- [ ] **Step 1: Construct the new repositories and pass them to the routes/services that need them**

Add these repository instances alongside the existing ones (near the other `Repository()`
constructions):

```kotlin
    val pairingsRepository = TournamentPairingsRepository()
    val roundRobinStandingsRepository = RoundRobinStandingsRepository(pairingsRepository, roundScoresRepository, roundsRepository)
```

Update the `ScoringService` construction to match its new constructor (Task 8):
```kotlin
    val scoringService = ScoringService(
        predictionsRepository,
        roundScoresRepository,
        adminMatchRepository,
        roundsRepository,
        tournamentRepository,
        pairingsRepository,
        roundRobinStandingsRepository,
    )
```

Update `LiveStateService` construction (Task 10):
```kotlin
    val liveStateService = LiveStateService(
        roundsRepository,
        adminMatchRepository,
        standingsRepository,
        roundRobinStandingsRepository,
        roundScoresRepository,
        tournamentRepository,
    )
```

Update the `routing { }` block:
- `tournamentRoutes(tournamentRepository)` → `tournamentRoutes(tournamentRepository, pairingsRepository)`
- `tournamentMatchRoutes(adminMatchRepository, roundsRepository, tournamentRepository, scoringService, auditLogRepository, liveStateService, liveHub)`
  → `tournamentMatchRoutes(adminMatchRepository, roundsRepository, tournamentRepository, pairingsRepository, scoringService, auditLogRepository, liveStateService, liveHub)`
- `standingsRoutes(standingsRepository, tournamentRepository)` → `standingsRoutes(standingsRepository, roundRobinStandingsRepository, tournamentRepository)`
- add a new line: `pairingsRoutes(pairingsRepository, tournamentRepository)`

Add the two new imports:
```kotlin
import com.predictrivals.roundrobin.RoundRobinStandingsRepository
import com.predictrivals.roundrobin.TournamentPairingsRepository
import com.predictrivals.roundrobin.pairingsRoutes
```

---

## Self-review

**Spec coverage:** schedule generation (Task 3/4) ✓, capped round creation (Task 7) ✓, matchday
scoring via existing goals conversion (Task 8) ✓, bye/BOT handling (Tasks 5/8) ✓, standings table
+ tie-breaks (Task 5/9) ✓, format acceptance at creation (Task 6) ✓, new pairings endpoint (Task
11) ✓, dead code removal (Tasks 2/8/9) ✓. `playoff` and per-user round_robin stats explicitly
called out as out-of-scope/known-gap inline rather than silently ignored.

**Placeholders:** none — every step has complete, concrete code; no TODOs.

**Type/name consistency:** `TournamentPairingsRepository`, `RoundRobinStandingsRepository`,
`PairingRecord`, `RoundRobinStandingRow` are spelled identically everywhere they're used across
Tasks 4–12 (cross-checked constructor signatures in Task 12 against each class's actual
constructor from Tasks 5/8/10).
