# Predict Rivals — Backend API Reference (for frontend & mobile)

This describes everything the backend exposes and the client-side rules needed to use it
correctly. For internal architecture/data model/security rationale, see the design docs:
[backend design](superpowers/specs/2026-08-14-backend-design.md) and
[multi-tournament design](superpowers/specs/2026-08-17-multi-tournament-design.md).

Base URL: wherever the Ktor app is deployed (local dev: `http://localhost:8080`).

---

## 1. Conventions

- All request/response bodies are JSON.
- Authenticated endpoints require `Authorization: Bearer <accessToken>`.
- Timestamps are ISO-8601 strings with timezone offset (e.g. `2026-08-20T15:00:00Z`).
- Errors always come back as:
  ```json
  { "error": "human-readable message" }
  ```
  with an appropriate HTTP status: `400` bad input, `401` not authenticated, `403` not
  authorized (not the tournament owner, or not a member), `404` not found, `409` conflict (e.g.
  tournament full, already started, duplicate email), `500` unexpected server error.
- IDs are numbers (`Long` on the backend) — treat them as opaque, don't assume ordering beyond "higher = created later".

---

## 2. Auth

Three sign-in methods, all converging on the same token pair. Store `accessToken` (short-lived,
used on every request) and `refreshToken` (long-lived, used only to get a new pair). (Phone/SMS
and Apple Sign In were both considered but cut on cost grounds — no free-forever SMS provider
exists for arbitrary numbers, and Apple Sign In requires a paid $99/year Apple Developer
Program membership with no free tier.)

### 2.1 Email/password

`POST /api/auth/email/register`
```json
// request
{ "name": "Jane Doe", "email": "jane@example.com", "password": "..." }
```
Returns `201` with the `AuthResponse` shape (below). `409` if the email is already registered.

`POST /api/auth/email/login`
```json
{ "email": "jane@example.com", "password": "..." }
```
Returns `200` `AuthResponse`, or `401` with a generic "Invalid email or password" (the backend
never reveals whether the email exists, so don't build UI that assumes it can).

Both endpoints are rate-limited (10 requests/minute per IP) — handle `429` by backing off.

### 2.2 Google / Facebook

Both take the client-obtained token and exchange it server-side (the backend verifies it
independently — never trust a client-side "logged in" state without this exchange):

```
POST /api/auth/google     { "idToken": "<Google ID token from the client SDK>" }
POST /api/auth/facebook   { "idToken": "<Facebook access token from the Facebook SDK>" }
```
Each returns `200` `AuthResponse`, or `401` if the token fails verification (expired, wrong
audience/app, tampered).

### 2.3 Refresh

`POST /api/auth/refresh`
```json
{ "refreshToken": "<stored refresh token>" }
```
Returns a new `{ "accessToken": "...", "refreshToken": "..." }` pair. **The refresh token is
single-use** — each call rotates it, so always overwrite your stored refresh token with the new
one from the response. If this call 401s, the session is dead and the user must sign in again.

### 2.4 Shapes

```ts
type AuthResponse = {
  tokens: { accessToken: string; refreshToken: string };
  user: { id: number; name: string; role: "player" | "admin" };
};
```

`role` is a vestigial global field from before multi-tournament support — it no longer gates
anything (tournament ownership does, see section 3). There is no separate "logout" endpoint —
logging out is a client-side action (discard the stored tokens).

---

## 3. Tournaments

Anyone signed in can create a tournament and becomes its **owner** — the only one who can curate
its matches. A tournament is joined by a short shared **join code**, not by browsing a public
list. Right now every tournament uses the `solo_points` format: everyone predicts the same
matches independently, no head-to-head pairing (round-robin/playoff formats are a planned
follow-up, not yet available).

### 3.1 Create

`POST /api/tournaments` (auth required)
```json
{ "name": "Friday Night League", "playerLimit": 20 }
```
`playerLimit` must be 2–50. The creator is automatically added as a player too (counted toward
the limit) as well as being the owner. Returns `201` `TournamentResponse` (below), including the
`joinCode` to share.

### 3.2 Join

`POST /api/tournaments/join` (auth required)
```json
{ "joinCode": "7F3K9Q" }
```
Case-insensitive. Returns `200` `TournamentResponse`. `404` if the code doesn't exist, `409` if
the tournament has already started or is full. Joining a second time as an existing member is a
no-op that just returns the current state (as long as it's still `open`).

**Auto-start:** if this join fills the tournament to its `playerLimit`, it flips to `active`
automatically in the same request — no separate call needed.

### 3.3 Start now

`POST /api/tournaments/{id}/start` (owner only)

No body. Flips an `open` tournament straight to `active` regardless of current player count —
even a lone owner can start and play solo. `403` if you're not the owner, `409` if it's already
started.

### 3.4 List mine / get one

`GET /api/tournaments/mine` (auth required) — tournaments you own or have joined:
```ts
type TournamentResponse[]
```

`GET /api/tournaments/{id}` (auth required, no membership needed) — lets someone preview a
tournament (name, status, player count) before deciding whether to join it with a shared code.

### 3.5 Shape

```ts
type TournamentResponse = {
  id: number;
  name: string;
  ownerUserId: number;
  joinCode: string;
  playerLimit: number;
  playerCount: number;
  format: "solo_points"; // only value in use today
  status: "open" | "active"; // open = accepting joins; active = started, matches can be created
  createdAt: string;
};
```

### 3.6 Lifecycle rules that affect the UI

- **Matches/rounds can only be created once a tournament is `active`.** If you build a "prepare
  round 1 while waiting for players" flow, it'll get `409` until the owner starts the tournament
  (or the cap is reached) — gate that UI on `status`.
- Everything below this point (matches, predictions, standings, live, calendar) is scoped to one
  tournament by `{tournamentId}` in the path, and requires the caller to be a **member** of that
  tournament (owner included, since owners auto-join) — `403` otherwise.

---

## 4. Match curation (owner only)

`GET /api/fixtures/candidates?league=<optional>&from=<date>&to=<date>` (auth required, **not**
owner-restricted — it's just public fixture search, useful for any owner building their own
tournament) — search upcoming real-world fixtures to build the next round from:
```ts
type FixtureCandidateResponse = {
  externalMatchId: string;
  league: string;
  homeTeam: string;
  awayTeam: string;
  kickoffAt: string;
}[]
```

`POST /api/tournaments/{id}/matches` (owner only, tournament must be `active`) — create a round
from exactly 9 selected fixtures:
```json
{
  "roundNumber": 1,
  "matches": [
    { "externalMatchId": "12345", "league": "Premier League", "homeTeam": "Arsenal", "awayTeam": "Chelsea", "kickoffAt": "2026-08-20T15:00:00Z" },
    /* ... exactly 9 total */
  ]
}
```
`400` if not exactly 9 matches, `403` if you're not the owner, `409` if the tournament hasn't
started. Returns `201` with the created `AdminMatchResponse[]`. Different tournaments can
independently feature the same real-world fixture — each gets its own row and its own scoring.

`PATCH /api/tournaments/{id}/matches/{matchId}/score` (owner only) — manual override (alongside
the automatic live sync):
```json
{ "homeScore": 2, "awayScore": 1, "status": "finished" }
```
`status` is optional (`"scheduled" | "live" | "finished"`) — omit it to only change the score.
Every call here is audit-logged server-side and immediately re-triggers scoring + a live
broadcast.

### AdminMatchResponse
```ts
type AdminMatchResponse = {
  id: number;
  tournamentId: number;
  externalMatchId: string;   // the provider's own match id, not usually needed by the UI
  league: string;
  homeTeam: string;
  awayTeam: string;
  kickoffAt: string;         // ISO-8601 — this is the prediction deadline for this match
  roundNumber: number;
  status: "scheduled" | "live" | "finished";
  homeScore: number | null;  // null until the match has started
  awayScore: number | null;
};
```

---

## 5. Calendar & rounds

`GET /api/tournaments/{id}/rounds/current` — the round the UI should show by default (prefers a
live round, then the next scheduled one, then the last finished one if nothing else exists yet):
```json
{ "id": 5, "tournamentId": 1, "roundNumber": 1, "status": "live" }
```
`status` is one of `"scheduled" | "live" | "finished"` and advances automatically as its matches
progress — you don't need to poll match statuses individually to know when a round is done.

`GET /api/tournaments/{id}/calendar` — every round with its matches, for a full past/upcoming
schedule view:
```json
[
  {
    "round": { "id": 1, "tournamentId": 1, "roundNumber": 1, "status": "finished" },
    "matches": [ /* AdminMatchResponse[], see section 4 */ ]
  },
  ...
]
```

---

## 6. Predictions

`POST /api/predictions` (auth required)
```json
{ "matchId": 42, "homeScore": 2, "awayScore": 1 }
```
Scores must be integers `0`–`20`. This is an **upsert** — calling it again for the same
`matchId` updates the existing prediction rather than creating a second one, so "submit" and
"edit" are the same call. You must be a member of the tournament that `matchId` belongs to (join
it first) — `403` otherwise.

**Deadline rule the UI must enforce itself:** predictions are only meaningful up until a match's
`kickoffAt`. The backend still accepts a late write (for transparency) but silently excludes it
from scoring — it will never error, but it also will never score. **Disable the prediction form
for a match once `kickoffAt` has passed** rather than relying on a server error to stop the user.

Response:
```ts
type PredictionResponse = {
  id: number;
  matchId: number;
  roundId: number;
  homeScore: number;
  awayScore: number;
  submittedAt: string;
  isLate: boolean;          // true = this prediction is excluded from scoring
  pointsAwarded: number | null; // null until the match has a result
  isExact: boolean | null;      // null until the match has a result; true = predicted the exact score
};
```

There is currently no "get my predictions for this round" GET endpoint — the client should track
what it submitted locally, or read prediction outcomes back via the user stats endpoint (section
8) once results are in. Flag to your backend contact if you need a dedicated list endpoint.

---

## 7. Live screen

Two ways to get the same data — use the WebSocket for the live in-progress screen, and the REST
endpoint anywhere else you just need a snapshot (e.g. deep-linking, refresh-on-focus).

`GET /api/tournaments/{id}/live` (auth via normal `Authorization` header) — current round's
matches + standings + in-progress round scores, one-shot.

`WS /ws/tournaments/{id}/live?token=<accessToken>` — same payload, pushed immediately on connect
and again every time a score changes (server polls the football provider every 5 minutes while
matches are live, so don't expect faster-than-that updates). No client→server messages are
needed; just listen.

**Auth is different for this one endpoint:** browsers' WebSocket API can't attach a custom
`Authorization` header to the handshake, so the access token goes as a `token` query parameter
instead. The server closes the connection immediately (policy violation) if the token is missing,
invalid, or the caller isn't a member of that tournament.

```ts
type LiveStateResponse = {
  matches: {
    id: number;
    homeTeam: string;
    awayTeam: string;
    kickoffAt: string;
    status: "scheduled" | "live" | "finished";
    homeScore: number | null;
    awayScore: number | null;
  }[];
  standings: {          // full tournament leaderboard, live-updating
    rank: number;
    userId: number;
    name: string;
    totalPoints: number;
    exactCount: number;
  }[];
  roundScores: {         // per-player points for the round currently in progress only
    userId: number;
    name: string;
    roundPoints: number; // provisional until the round finishes, but the total never changes after
  }[];
};
```

**Important:** extra time and penalty shootouts are never reflected here — if a match goes past
90 minutes + stoppage, the score you see is locked in at that point and won't change again even
if the provider later reports an ET/penalty result. Don't build UI that expects an ET/penalty
score to eventually appear.

---

## 8. Standings, top scorers, personal stats

All three require tournament membership (`403` otherwise).

`GET /api/tournaments/{id}/standings` — the live leaderboard:
```ts
type SoloStandingEntryResponse = {
  rank: number;
  userId: number;
  name: string;
  totalPoints: number;
  exactCount: number;
}[]
```
An exact-score prediction is worth 3 points directly (not tracked as a separate "goal" unit, since
`solo_points` tournaments have no head-to-head match to convert goals for). Tie-break is
`totalPoints` then `exactCount`.

`GET /api/tournaments/{id}/top-scorers` — same ranking as standings today (there's no separate
"goals" metric in `solo_points`; this endpoint exists for API consistency with formats coming
later where it will differ).

`GET /api/tournaments/{id}/users/{userId}/stats` — a specific player's numbers **for this
tournament** (a user's stats can differ across the different tournaments they belong to):
```ts
type SoloUserStatsResponse = {
  userId: number;
  name: string;
  totalPoints: number;
  exactCount: number;
  totalPredictions: number;
  scoredPredictions: number;  // predictions whose match has finished and been scored
  accuracy: number;           // 0.0–1.0, correct-or-better predictions ÷ scored predictions
};
```

---

## 9. Scoring rules (for building result/points UI)

| Prediction vs. actual result | Award |
|---|---|
| Exact score (including exact draws) | **3 points** |
| Correct win/loss + correct goal difference, not exact | 2 points |
| Correct win/loss, wrong goal difference | 1 point |
| Correct draw, wrong exact score | 1 point (always — draws never get 2, even though the "difference" of 0 also matches) |
| Wrong outcome | 0 |

Unlike the internal per-prediction storage (where an exact score is tracked as a separate
"exact" flag rather than literal points), the **standings total** you see via the API always
already includes that conversion — `totalPoints` is ready to display as-is, no client-side math
needed.

---

## 10. What's intentionally not here yet

- Round-robin and playoff tournament formats — only `solo_points` exists today; tournament
  creation doesn't accept a `format` field yet because there's nothing else to choose.
- No endpoint to list "my predictions for a round" — track submissions client-side for now.
- No way for an owner to remove a player, change the player limit, or transfer ownership after
  creation.
- No explicit logout/session-revocation endpoint — discard tokens client-side.
- No push notifications — the live experience is WebSocket-only.

Flag any of these to your backend contact if the frontend/mobile build actually needs them.
