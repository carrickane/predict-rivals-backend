# Round-Robin Tournament Format — Design

Date: 2026-08-18
Scope: implement a genuine head-to-head `round_robin` tournament format — fixture schedule
generation, per-matchday player pairing, and league-table standings. Builds on
[2026-08-17-multi-tournament-design.md](2026-08-17-multi-tournament-design.md), which explicitly
deferred this ("player pairing/scheduling, bye handling, tie-break rules for head-to-head play").
`playoff` stays deferred — separate future spec, shown in the client dropdown only as a disabled
"coming soon" option.

**Client-side companion work** (KMM/Compose, in `predict-rivals-android`) is covered in section 7.

## 1. What changes architecturally

Tournament creation gains a selectable format, and `round_robin` tournaments gain a fixed,
finite fixture schedule generated once the tournament starts — a real departure from
`solo_points`, where the owner curates rounds indefinitely for as long as they want.

- `POST /api/tournaments` accepts `format: "solo_points" | "round_robin"` (default
  `"round_robin"`, since that's this app's primary mode now). `"playoff"` is rejected (400) —
  not creatable yet.
- On tournament start (owner-triggered `POST /{id}/start`, or auto-start hitting the player
  cap — same triggers as today, unchanged), a `round_robin` tournament's full fixture schedule
  is generated immediately, using the final member list at that moment (joining is already
  blocked once a tournament leaves `open` status, so the roster is final).
- Round/match curation is **unchanged in mechanism** — the owner still picks exactly 9
  real-world fixtures per round, one shared set of matches for every player that round. Pairing
  is a comparison layer on top of that shared round, not a different question set per pair. The
  owner can't curate a round number beyond what the generated schedule has.

## 2. Schedule generation

Standard round-robin circle method, **2 legs** (every pair plays twice):

- N players, N even: `N - 1` matchdays per leg → `2 × (N - 1)` total rounds.
- N players, N odd: `N` matchdays per leg (one bye per player per leg) → `2 × N` total rounds.

Generated once, at start time, into a new table:

```
game.tournament_pairings
  tournament_id   (FK -> game.tournaments)
  round_number    (matches game.rounds.round_number for that tournament — same numbering, no
                   separate "matchday" concept)
  player_a_user_id (FK -> game.users)
  player_b_user_id (FK -> game.users, NULLABLE — null means player_a's bye/BOT round that
                    round_number)
```

No home/away distinction (no gameplay asymmetry exists between the two sides of a pairing), so
the pair is unordered in practice — `player_a`/`player_b` naming is just storage, not semantics.
A leg-2 fixture is simply the same unordered pair recurring at a later `round_number`; no
separate "leg" field needed.

## 3. Bye / BOT matchday

`player_b_user_id IS NULL` marks a bye round for `player_a`. The client shows this as
"vs. BOT" (cosmetic label only — no bot account, no bot predictions, nothing in `game.users`)
rather than a bare "bye", so nobody sees a blank round. The bye player still submits real
predictions for that round like any other, and their own goals-for that round still accumulates
into their personal cumulative total (section 5). The round contributes **zero** league points,
wins/draws/losses, or goals-against — entirely excluded from head-to-head standings math.

## 4. Matchday result

Reuses the existing, already-correct `ScoringEngine.convertToGoals` / `RoundScoresRepository`
machinery unchanged — each player's round performance already converts to a per-round "goals"
number once every match in that round is scored, and gets frozen when the round finishes
(`RoundScoresRepository.freeze`, already wired via `ScoringService.recalculateMatch`). What's new
is comparing the two paired players' frozen round-goals once *both* are frozen:

- More goals → win (3 league points to that player)
- Equal goals → draw (1 league point each)
- Fewer goals → loss (0 league points)

This computation runs once both sides of a `round_number`'s pairing have a frozen
`RoundScoresTable` row (i.e., once that round finishes for both players — which it always does
together, since they share the same round's matches).

## 5. Standings

The existing dormant `game.standings` table (and `StandingsRepository.applyFinishedRound`) was
built for a flat individual-accumulation model that doesn't match real head-to-head pairing —
it's being replaced, not reused, for `round_robin`. New table:

```
game.round_robin_standings
  tournament_id   (FK -> game.tournaments)
  user_id         (FK -> game.users)
  league_points   int
  wins            int
  draws           int
  losses          int
  goals_for       int   -- sum of this player's own round-goals across every round they played,
                         -- including bye/BOT rounds (a personal stat, opponent-independent)
  goals_against   int   -- sum of the paired opponent's round-goals, across non-bye rounds only
  updated_at
```

Updated once per finished matchday, for both paired players at once (or just the one player, for
a bye round — `goals_for` only, nothing else moves).

**Ranking order:** league points → goals scored (`goals_for`) → wins → goal difference
(`goals_for - goals_against`, derived, not stored) → head-to-head (look up the `tournament_pairings`
row(s) between the specific tied players and take the result of that matchday from section 4).
If still unresolved after all four criteria (rare — e.g. 3-way tie unresolved by pairwise
head-to-head), fall back to `user_id` for a stable, deterministic order.

The old `game.standings` / `applyFinishedRound` path being dead for both formats now
(`solo_points` never used it; `round_robin` uses the new table instead) is worth removing as
part of this work rather than leaving misleading dormant code behind.

## 6. API changes

- `POST /api/tournaments` — request gains `format` (section 1).
- `GET /api/tournaments/{id}/standings` — for `round_robin` tournaments, returns the new league
  table shape (rank, userId, name, leaguePoints, wins, draws, losses, goalsFor, goalsAgainst,
  goalDifference) instead of the flat solo shape. This **is** a genuinely different response
  shape for round_robin — not wire-compatible with the solo shape (unlike what an earlier,
  now-superseded version of this design assumed), since a real league table has no solo
  equivalent for W/D/L/GF/GA.
- `GET /api/tournaments/{id}/top-scorers` — for `round_robin`, ranks by `goals_for` alone (the
  literal "who scored the most goals" metric), distinct from the standings ranking.
- `GET /api/tournaments/{id}/live` — for `round_robin`, the current round's payload additionally
  needs each player's opponent for that round_number (from `tournament_pairings`), so the live/
  predictions screens can show "this round: vs. Player Y" (or "vs. BOT").
- New: `GET /api/tournaments/{id}/pairings` — full schedule, all rounds, for a schedule/fixture
  view (needed so players can see upcoming opponents, not just the current round).

## 7. Client changes (`predict-rivals-android`)

- `CreateTournamentRequestDto` gains `format`; `TournamentRepository.create()` passes it through.
- `CreateTournamentScreen` gets a dropdown (Material3 `ExposedDropdownMenuBox`): "Round robin"
  (default/pre-selected), "Solo", "Playoff" (shown, disabled, "coming soon").
- New standings DTO/UI for the round_robin league-table shape (W/D/L/GF/GA/Pts columns) —
  distinct from the existing flat solo `StandingsScreen`, since the response shape itself
  differs now (section 6).
- Live/predictions screens gain an opponent indicator for round_robin tournaments ("This round:
  vs. Player Y" / "vs. BOT").
- New schedule/fixtures screen (or a section of the existing calendar screen) surfacing the full
  pairing list from the new `/pairings` endpoint.

## 8. Out of scope

- `playoff` format (separate future spec — bracket/elimination logic doesn't exist and isn't
  designed yet).
- Mid-tournament roster changes — schedule is generated once at start and fixed, consistent with
  the app's existing no-roster-changes-after-start rule.
- Any UI/API for editing a generated schedule.
