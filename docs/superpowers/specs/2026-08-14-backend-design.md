# Predict Rivals — Backend Design

Date: 2026-08-14
Scope: backend only (game API, admin API, scoring engine, football data integration, live sync, auth). Frontend and mobile apps are out of scope for this spec.

## 1. Purpose

Backend for a football prediction tournament: players submit predictions for 9 curated matches per round, the system pulls live results from an external football API, scores predictions automatically, and serves a leaderboard and top-scorer ("bombardier") list. Full product context: see `predictPrompt.md` at the repo root's parent directory (original spec document).

## 2. Tech stack

- **Language/framework:** Kotlin + Ktor
- **Persistence:** Exposed (JetBrains) over PostgreSQL
- **Migrations:** Flyway, versioned SQL files in `db/migration`
- **Testing:** Kotest + Testcontainers (tests run against a real Postgres, not mocks)
- **Local dev:** Docker Compose (Postgres only; app runs via Gradle)
- **Deployment:** Railway (JVM app + Postgres add-on)
- **Live push:** WebSocket (Ktor's built-in WebSocket support)
- **External data:** API-Football (single provider, both fixtures and live scores), behind an internal adapter interface

## 3. Architecture

Layered structure, routes → services → repositories. Package layout:

```
src/main/kotlin/
  auth/          # AuthProvider interface + EmailPassword/Google/Apple/Facebook/PhoneSms impls, JWT issuance
  tournament/    # Tournament, join-tournament
  rounds/        # Rounds, round lifecycle (scheduled -> live -> finished)
  adminMatches/  # AdminMatches CRUD (admin_ref schema), fixture curation from football API
  predictions/   # submit/update predictions, deadline validation
  scoring/       # scoring engine - points/goals calculation, round-level conversion
  standings/     # standings + top-scorers aggregation (derived queries, not stored duplicates)
  footballApi/   # FootballDataProvider interface + API-Football implementation, request budgeting
  liveSync/      # background polling worker
  live/          # WebSocket session hub, broadcast on score/points change
  plugins/       # Ktor setup: routing, serialization, auth, CORS, error handling
```

Each package exposes a service interface to the rest of the app, not its internals. `scoring` has no knowledge of WebSockets; `live` has no knowledge of scoring math; `liveSync` orchestrates by calling `scoring.recalculate(matchId)` and `live.broadcastUpdate(...)`.

**Deliberate deviation from the literal source spec:** Calendar and Top Scorers are implemented as derived queries, not physically stored/duplicated tables — Calendar is a join across `admin_matches`/`rounds`, Top Scorers is Standings ordered by goals. Storing them separately would risk drift with no benefit.

## 4. Data model

Single Postgres instance, two schemas:

### `admin_ref` schema

**`admin_matches`**
- id, external_match_id, league, home_team, away_team, kickoff_at
- round_number
- status: `scheduled` / `live` / `finished`
- home_score, away_score
- updated_at

### `game` schema

**`users`**
- id, name, email (nullable), phone (nullable), avatar_url, created_at

**`auth_identities`**
- id, user_id, provider (`email` / `google` / `apple` / `facebook` / `phone`), provider_user_id, password_hash (email provider only), created_at
- unique constraint on (provider, provider_user_id) — allows a user to have multiple linked providers without changing `users`

**`tournaments`**
- id, name, season, start_date, end_date

**`tournament_memberships`**
- user_id, tournament_id, joined_at
- existence of this row is the access gate for submitting predictions

**`rounds`**
- id, tournament_id, round_number, status (`scheduled` / `live` / `finished`)

**`predictions`**
- id, user_id, match_id (FK -> `admin_ref.admin_matches`), round_id
- predicted_home_score, predicted_away_score
- submitted_at, updated_at, is_late
- points_awarded (nullable), is_exact (nullable) — recalculated on every live score change to the match; values are provisional until the match reaches `finished`, then final

**`round_scores`** (per user per round snapshot)
- user_id, round_id, points_raw, exact_count, goals_awarded, computed_at
- recomputed on every live score change during the round; **frozen** once all 9 of the round's matches reach `finished`

**`standings`**
- user_id, tournament_id, total_goals, total_exact_scores, rounds_played, updated_at
- `total_goals` = sum of `round_scores.goals_awarded` across finished rounds only; rank computed at query time (not stored)

## 5. Scoring rules

Applied per prediction once its match is live/finished with a score:

| Case | Award |
|---|---|
| Exact score (any outcome, including draws) | 1 goal, direct |
| Correct win/loss outcome + correct goal difference (not exact) | 2 points |
| Correct win/loss outcome, wrong goal difference | 1 point |
| Correct draw, wrong exact score | 1 point (always — never 2, even though the goal difference for a draw is technically also correct) |
| Wrong outcome | 0 |

**Round-level goal conversion:** `round_scores.goals_awarded = floor(points_raw / 3) + exact_count`. This is computed continuously while the round is live (for the live screen's provisional display) but only **frozen** once all 9 matches in the round reach `finished`. Points do not carry over to the next round — any remainder below the next multiple of 3 is discarded at freeze time.

Other business rules:
- A prediction submitted after its match's kickoff time is marked `is_late` and excluded from scoring.
- Predictions are editable up until that specific match's kickoff.
- A user must have a `tournament_memberships` row before submitting predictions.

## 6. Auth

Common interface, shared JWT session issuance regardless of provider:

```kotlin
interface AuthProvider {
    val type: AuthProviderType
    suspend fun authenticate(credentials: ProviderCredentials): AuthResult
}
```

All five methods implemented in this pass:
- **Email/password** — bcrypt hash, register + login
- **Google** — verify Google ID token against Google's JWKs
- **Apple** — verify Apple identity token (JWT) against Apple's public keys
- **Facebook** — verify access token via Graph API debug_token
- **Phone/SMS** — two-step (request code via Twilio, verify code). Twilio is the default SMS delivery provider; swap later if needed.

Every provider normalizes to the same `AuthResult`, which feeds one JWT issuance path. Joining a tournament (`tournament_memberships`) is a separate authenticated action, not part of registration.

## 7. Football data integration (API-Football only)

Adapter interface, so the provider is swappable later without touching business logic:

```kotlin
interface FootballDataProvider {
    suspend fun searchUpcomingFixtures(league: String?, dateRange: DateRange): List<FixtureDto>
    suspend fun getLiveScores(matchIds: List<String>): List<LiveScoreDto>
}
```

A `RequestBudgetTracker` enforces the free-tier 100 req/day cap: only matches currently `live` are polled, and polling backs off (logged, not silently dropped) as the daily quota runs low.

### Polling rules
- **Interval:** every 5 minutes while a match is live.
- **Window:** only during normal match time (1st half / half-time / 2nd half). Polling stops once the match reaches full-time.
- **Extra time / penalties are never polled or scored.** The score at full-time (90 minutes + stoppage) is treated as final for prediction purposes. If a match goes to extra time or a penalty shootout, that outcome never reaches the system — predictions score against the regulation result only.

**Status granularity note:** `admin_matches.status` only stores the three coarse states from section 4 (`scheduled` / `live` / `finished`) — the provider's finer-grained match status (1H/HT/2H/FT/AET/P/...) is never persisted. Each poll, the live sync worker inspects the *provider's* raw status transiently: while it's one of {1H, HT, 2H}, keep polling and treat the score as provisional; the moment it's anything else (FT, AET, P, or any post-regulation state), stop polling that match, set our own `status = finished`, and freeze the score exactly as last polled. This handles providers that jump straight from `2H` to an extra-time state without an explicit `FT` transition — the rule is "stop the instant we leave normal time," not "wait for an explicit FT signal."

## 8. Live sync worker

A coroutine loop in `liveSync`:
1. Flips `admin_matches.status` from `scheduled` to `live` when kickoff time passes.
2. Polls currently-live matches via `FootballDataProvider.getLiveScores` per the rules in section 7.
3. On score change: updates `admin_matches`, recalculates affected `predictions`, `round_scores`, and `standings`, then broadcasts via the WebSocket hub.
4. Marks a match `finished` at full-time and, once all 9 matches in a round are `finished`, freezes that round's `round_scores` (applying the goal conversion in section 5).

## 9. Live updates (WebSocket)

Endpoint: `/ws/live`. In-memory broadcast hub (single Railway instance for now — no Redis pub/sub needed unless this scales beyond one instance later).

Broadcast payload (same shape returned by `GET /api/live`):

```jsonc
{
  "matches": [ /* this round's 9 admin_matches: teams, current score, status */ ],
  "standings": [ /* full tournament table: user, total_goals, total_exact_scores, rank */ ],
  "roundScores": [ /* per-player breakdown for the round in progress: points_raw so far, provisional goals */ ]
}
```

This gives the live screen real match scores, the live standings table, and player-vs-player round comparison from one source, both over the initial GET and every subsequent WebSocket push.

## 10. API surface

**Admin**
- `POST /api/admin/matches` — create the round's 9 matches (candidates sourced from `FootballDataProvider.searchUpcomingFixtures`)
- `PATCH /api/admin/matches/:id/score` — manual score override, alongside the automatic live sync worker

**Players**
- `POST /api/predictions` — submit/update a prediction (deadline-validated against match kickoff)
- `POST /api/tournament/join` — join the tournament

**Shared**
- `GET /api/calendar` — all rounds/matches (derived query)
- `GET /api/rounds/current` — current active round
- `GET /api/live` — live state per section 9
- `GET /api/standings` — tournament leaderboard
- `GET /api/top-scorers` — top-scorer list (derived from standings)
- `GET /api/users/:id/stats` — a player's own prediction history, accuracy, trend

**Auth** — one endpoint per provider (register/login for email; token-exchange for Google/Apple/Facebook; request-code/verify-code for phone), all converging on shared JWT issuance.

## 11. Testing & deployment

- Kotest + Testcontainers: integration tests run against a real Postgres instance, not mocks.
- Flyway migrations versioned under `db/migration`, applied on startup.
- Local dev: `docker-compose.yml` for Postgres; app runs via Gradle against it.
- Deployment: Railway, JVM app + Postgres add-on.
