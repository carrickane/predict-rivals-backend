# Multi-Tournament (Phase 1) — Design

Date: 2026-08-17
Scope: replace the single-global-tournament assumption with user-created, joinable tournaments
(create/join-by-code/capacity/start-trigger/per-tournament ownership and match curation), plus
the "solo points" scoring format. Builds on the existing backend design
([2026-08-14-backend-design.md](2026-08-14-backend-design.md)).

**Explicitly deferred to a follow-up spec:** round-robin and playoff tournament formats (player
pairing/scheduling, bye handling, tie-break rules for head-to-head play). This spec only
implements the `solo_points` format; the schema reserves the other two format names so phase 2
doesn't need another migration.

## 1. What changes architecturally

Today, almost everything resolves "the" tournament implicitly via
`TournamentRepository.findActiveTournament()` — a single global tournament assumption. That's
gone. A user can belong to multiple tournaments, so every tournament-scoped route moves from an
implicit lookup to an explicit `{tournamentId}` in the path.

**Route restructuring:**

| Old | New |
|---|---|
| `POST /api/tournament/join` | `POST /api/tournaments` (create), `POST /api/tournaments/join` (join by code) |
| — | `POST /api/tournaments/{id}/start` (owner-only manual start) |
| — | `GET /api/tournaments/mine` (tournaments you own or belong to) |
| — | `GET /api/tournaments/{id}` (details: name, status, owner, player count/limit, join code) |
| `POST /api/admin/matches` | `POST /api/tournaments/{id}/matches` (owner-only) |
| `PATCH /api/admin/matches/:id/score` | `PATCH /api/tournaments/{id}/matches/{matchId}/score` (owner-only) |
| `GET /api/admin/fixtures/candidates` | `GET /api/fixtures/candidates` — unscoped; public fixture search, no owner gate needed |
| `GET /api/rounds/current` | `GET /api/tournaments/{id}/rounds/current` |
| `GET /api/calendar` | `GET /api/tournaments/{id}/calendar` |
| `GET /api/live`, `/ws/live` | `GET /api/tournaments/{id}/live`, `/ws/tournaments/{id}/live` |
| `GET /api/standings`, `/api/top-scorers` | `GET /api/tournaments/{id}/standings`, `/top-scorers` |
| `GET /api/users/:id/stats` | `GET /api/tournaments/{id}/users/{userId}/stats` |
| `POST /api/predictions` | unchanged — the match itself carries which tournament it belongs to |

**Authorization shift:** the global `role = admin` check is dropped entirely for match curation.
Owner-only actions check `tournament.ownerUserId == <caller>` instead, scoped to that one
tournament. Standings/live/calendar/rounds require tournament membership (must have joined).
`GET /api/tournaments/{id}` itself stays open to any authenticated user, so someone can preview a
tournament (name, status, player count) before joining it with a shared code.

## 2. Data model changes

### `game.tournaments` (rewritten)

Drops `season` / `start_date` / `end_date` — those only made sense under the old date-range
"the active tournament" model; lifecycle is explicit now.

- `id`, `name`
- `owner_user_id` (FK → `game.users`) — the creator; also gets a `tournament_memberships` row,
  since owners auto-join as players (counted toward the player limit)
- `join_code` — unique, auto-generated: 6 uppercase alphanumeric characters, excluding
  easily-confused characters (`0`/`O`, `1`/`I`), e.g. `7F3K9Q`; generated with a collision check
  and retry. What gets shared to invite others.
- `player_limit` — 2–50, chosen by the owner at creation
- `format` — `solo_points` / `round_robin` / `playoff`. Only `solo_points` is accepted by the API
  in this phase; the other two are reserved in the schema so phase 2 doesn't need another
  migration, just new code paths.
- `status` — `open` (accepting joins) → `active` (started; matches/rounds can now be created). No
  further states needed yet.
- `created_at`

### `admin_ref.admin_matches`

Gains `tournament_id` (FK → `game.tournaments`) — matches are curated per-tournament now, not
globally. Round numbers are scoped per tournament (each tournament has its own round 1, round 2,
...).

**Found during implementation:** `external_match_id` was globally unique under the old
single-tournament model. That breaks now — two different tournaments can legitimately feature the
same real-world fixture in their own round (each owner curates independently). Uniqueness moves
to the `(tournament_id, external_match_id)` pair instead. The live-sync worker groups live matches
by `external_match_id` before polling, so a fixture shared by multiple tournaments is still only
polled once (one API-Football request, not one per tournament) and the result is applied to every
tournament's copy.

### Unchanged

`game.tournament_memberships`, `game.rounds` were already tournament-scoped correctly — reused
as-is. `game.standings` / `game.round_scores` stay in the schema but go **unused** for
`solo_points` tournaments (see section 3) — reserved, dormant, for the round-robin/playoff spec.

### Capacity + auto-start

Joining checks `status == 'open'` and `current member count < player_limit` inside the same
transaction as the membership insert; if that insert brings the count to exactly `player_limit`,
the tournament flips to `active` in that same request. (Small race-condition edge case with two
near-simultaneous joins exactly at the cap boundary — acceptable at this scale, not engineered
around.)

### Lifecycle-gated actions

- Rounds/matches can only be created once `status == 'active'` (i.e. after start) — attempting
  before that returns a `409 Conflict` ("tournament hasn't started yet").
- Joining a `active`-status tournament (already started) is rejected with `409 Conflict`
  ("tournament has already started").
- Joining a full tournament is rejected with `409 Conflict` ("tournament is full").
- Joining with an unknown code returns `404 Not Found`.
- Owner-only actions attempted by a non-owner return `403 Forbidden`.

## 3. Scoring for the solo points format

`ScoringEngine.score()` itself is unchanged — same per-prediction rules (exact / correct+diff /
correct-only / draw / wrong). What changes is how the **leaderboard total** is computed for
`solo_points` tournaments:

- **No goal conversion, no round freezing** — that machinery is bypassed entirely for this
  format; it exists specifically for round-robin/playoff's "a round's goals become a head-to-head
  match score" mechanic (phase 2).
- **Leaderboard total** = `sum(pointsAwarded across all non-late, scored predictions) +
  (exactCount × 3)` — an exact score counts as 3 points directly, not as a separate "goal" unit.
- **Computed live**, not stored incrementally. A prediction's score can change repeatedly while
  its match is live; maintaining a running per-user total would mean tracking deltas on every
  update. A live `SUM`/`COUNT` aggregate query is simpler, always correct, and fast enough at this
  scale (a handful of tournaments, ≤50 players, 9 predictions/round).
- **`ScoringService.recalculateMatch()`** becomes format-conditional: for `solo_points`, it
  updates each affected prediction's `pointsAwarded`/`isExact` and stops there — no `round_scores`
  write, no freeze check, no `standings` table write.
- **Found during implementation:** `rounds.status` (`scheduled` → `live` → `finished`) still has
  to advance for every format, including `solo_points` — it's what "current round" detection
  relies on (`GET .../rounds/current`, and the live payload's match list). This is decoupled from
  the round-scores-freeze-then-apply-to-standings step: a small format-independent
  `updateRoundLifecycleStatus` runs on every `recalculateMatch()` call regardless of format, while
  the freeze/standings-apply step remains solo_points-skipped as above. Without this split, a
  solo tournament's "current round" would get stuck on round 1 forever once round-freezing is
  bypassed for the format.
- **Top scorers** = identical ranking to standings for this format (there's no separate "goals"
  unit to rank by differently) — same underlying query, exposed under both endpoints for API
  consistency with the formats coming in phase 2.
- **Tie-break:** total points, then exact-count.
- **Per-user stats** (`GET /api/tournaments/{id}/users/{userId}/stats`): total points, exact
  count, total predictions, scored predictions, accuracy — all computed live, scoped to that one
  tournament (a user's stats can now differ across tournaments they belong to).

## 4. Tournament creation & join flow

- `POST /api/tournaments` — body: `{ name, playerLimit }` (format defaults to, and for now only
  accepts, `solo_points`). Creates the tournament with `status = open`, generates a unique
  `join_code`, and adds the creator as both `owner_user_id` and a `tournament_memberships` row.
- `POST /api/tournaments/join` — body: `{ joinCode }`. Validates the code exists, the tournament
  is `open`, and it isn't full; inserts membership; auto-flips to `active` if that fills it.
- `POST /api/tournaments/{id}/start` — owner-only, only valid while `status == open`; flips to
  `active` regardless of current player count. No minimum enforced — even a lone owner (nobody
  else has joined yet) can start and play solo.
- `GET /api/tournaments/mine` — tournaments where the caller is the owner or a member.
- `GET /api/tournaments/{id}` — open to any authenticated user (preview-before-joining use case).

## 5. What's explicitly not in this phase

- Round-robin and playoff formats — pairing/schedule generation, bye handling for odd player
  counts, tie-break rules for head-to-head play. Separate spec once this foundation ships.
- Any tournament "completed" end state beyond `open`/`active`.
- Any capability for an owner to remove a player, change the player limit after creation, or
  transfer ownership.
