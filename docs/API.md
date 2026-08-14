# Predict Rivals — Backend API Reference (for frontend & mobile)

This describes everything the backend exposes and the client-side rules needed to use it
correctly. For internal architecture/data model/security rationale, see
[docs/superpowers/specs/2026-08-14-backend-design.md](superpowers/specs/2026-08-14-backend-design.md).

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
  authorized (wrong role, or not a tournament member), `404` not found, `409` conflict
  (e.g. duplicate email), `500` unexpected server error.
- IDs are numbers (`Long` on the backend) — treat them as opaque, don't assume ordering beyond "higher = created later".

---

## 2. Auth

Five sign-in methods, all converging on the same token pair. Store `accessToken` (short-lived,
used on every request) and `refreshToken` (long-lived, used only to get a new pair).

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

### 2.2 Google / Apple / Facebook

All three take the client-obtained token and exchange it server-side (the backend verifies it
independently — never trust a client-side "logged in" state without this exchange):

```
POST /api/auth/google     { "idToken": "<Google ID token from the client SDK>" }
POST /api/auth/apple      { "idToken": "<Apple identity token from Sign in with Apple>" }
POST /api/auth/facebook   { "idToken": "<Facebook access token from the Facebook SDK>" }
```
Each returns `200` `AuthResponse`, or `401` if the token fails verification (expired, wrong
audience/app, tampered).

### 2.3 Phone / SMS

Two steps:

`POST /api/auth/phone/request-code`
```json
{ "phone": "+15551234567" }
```
Returns `202 Accepted` with no body. Triggers an SMS with a 6-digit code (expires in 5 minutes).
Rate-limited to 3 requests/10 minutes per IP — show a "resend" cooldown in the UI rather than
letting users spam this.

`POST /api/auth/phone/verify`
```json
{ "phone": "+15551234567", "code": "123456" }
```
Returns `200` `AuthResponse`, or `401` if the code is wrong, expired, or already used (each code
allows at most 5 verify attempts). Rate-limited to 5 requests/10 minutes per IP.

### 2.4 Refresh

`POST /api/auth/refresh`
```json
{ "refreshToken": "<stored refresh token>" }
```
Returns a new `{ "accessToken": "...", "refreshToken": "..." }` pair. **The refresh token is
single-use** — each call rotates it, so always overwrite your stored refresh token with the new
one from the response. If this call 401s, the session is dead and the user must sign in again.

### 2.5 Shapes

```ts
type AuthResponse = {
  tokens: { accessToken: string; refreshToken: string };
  user: { id: number; name: string; role: "player" | "admin" };
};
```

There is no separate "logout" endpoint — logging out is a client-side action (discard the stored
tokens). The refresh token remains valid server-side until it expires or is rotated by use; if you
need hard revocation later, that would be a backend addition.

---

## 3. Joining the tournament

Predictions and most gameplay actions require the player to have explicitly joined. Do this once,
right after first sign-in (or gate the prediction UI on it and prompt when they try to predict).

`POST /api/tournament/join` (auth required)

No body. Returns:
```json
{ "tournamentId": 1, "joinedAt": "2026-08-14T12:00:00Z" }
```
Idempotent — calling it again for an already-joined user just returns the original join info, no
error.

If a player hasn't joined and calls `POST /api/predictions`, they get `403 Forbidden`
("Join the tournament before submitting predictions") — use that to drive a "join first" prompt.

---

## 4. Calendar & rounds

`GET /api/rounds/current` — the round the UI should show by default (prefers a live round, then
the next scheduled one, then the last finished one if nothing else exists yet):
```json
{ "id": 5, "tournamentId": 1, "roundNumber": 5, "status": "scheduled" }
```
`status` is one of `"scheduled" | "live" | "finished"`.

`GET /api/calendar` — every round with its matches, for a full past/upcoming schedule view:
```json
[
  {
    "round": { "id": 1, "tournamentId": 1, "roundNumber": 1, "status": "finished" },
    "matches": [ /* AdminMatchResponse[], see below */ ]
  },
  ...
]
```

### AdminMatchResponse
```ts
type AdminMatchResponse = {
  id: number;
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

## 5. Predictions

`POST /api/predictions` (auth required)
```json
{ "matchId": 42, "homeScore": 2, "awayScore": 1 }
```
Scores must be integers `0`–`20`. This is an **upsert** — calling it again for the same
`matchId` updates the existing prediction rather than creating a second one, so "submit" and
"edit" are the same call.

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
7) once results are in. Flag to your backend contact if you need a dedicated list endpoint.

---

## 6. Live screen

Two ways to get the same data — use the WebSocket for the live in-progress screen, and the REST
endpoint anywhere else you just need a snapshot (e.g. deep-linking, refresh-on-focus).

`GET /api/live` — current round's matches + standings + in-progress round scores, one-shot.

`WS /ws/live` — same payload, pushed immediately on connect and again every time a score changes
(server polls the football provider every 5 minutes while matches are live, so don't expect
faster-than-that updates). No client→server messages are needed; just listen.

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
  standings: {          // full tournament table, with this round's provisional goals layered in
    rank: number;
    userId: number;
    name: string;
    totalGoals: number;
    totalExactScores: number;
  }[];
  roundScores: {         // per-player breakdown for the round currently in progress only
    userId: number;
    name: string;
    pointsRaw: number;       // raw points before the 3-points-=-1-goal conversion
    provisionalGoals: number; // this round's goals so far — NOT final until the round finishes
  }[];
};
```

**Important:** extra time and penalty shootouts are never reflected here — if a match goes past
90 minutes + stoppage, the score you see is locked in at that point and won't change again even
if the provider later reports an ET/penalty result. Don't build UI that expects an ET/penalty
score to eventually appear.

`roundScores` entries disappear once the round is fully finished (all 9 matches finished) — at
that point their goals have been folded into `standings.totalGoals` permanently, and any leftover
points below a multiple of 3 are discarded, not carried to the next round.

---

## 7. Standings, top scorers, personal stats

`GET /api/standings` — the authoritative leaderboard (finished rounds only, no in-progress
round included — use `/api/live` if you want the live-updating version):
```ts
type StandingEntryResponse = {
  rank: number;
  userId: number;
  name: string;
  totalGoals: number;
  totalExactScores: number;
  roundsPlayed: number;
}[]
```

`GET /api/top-scorers` — same ranking, "bombardier" framing:
```ts
type TopScorerEntryResponse = { rank: number; userId: number; name: string; totalGoals: number }[]
```

`GET /api/users/{id}/stats` — a specific player's own numbers:
```ts
type UserStatsResponse = {
  userId: number;
  name: string;
  totalGoals: number;
  totalExactScores: number;
  roundsPlayed: number;
  totalPredictions: number;
  scoredPredictions: number;  // predictions whose match has finished and been scored
  accuracy: number;           // 0.0–1.0, correct-or-better predictions ÷ scored predictions
};
```
No auth is enforced on this one currently (any signed-in-or-not client can view any user's public
stats) — call out to your backend contact if per-user privacy is needed later.

---

## 8. Scoring rules (for building result/points UI)

| Prediction vs. actual result | Award |
|---|---|
| Exact score (including exact draws) | **1 goal**, direct |
| Correct win/loss + correct goal difference, not exact | 2 points |
| Correct win/loss, wrong goal difference | 1 point |
| Correct draw, wrong exact score | 1 point (always — draws never get 2, even though the "difference" of 0 also matches) |
| Wrong outcome | 0 |

Points convert to goals at the end of each round: `goals = floor(points / 3) + exactScoreCount`.
Any remaining points below the next multiple of 3 are **discarded**, not carried into the next
round. This means a player's `pointsAwarded` on an individual `PredictionResponse` is not
directly "their score" — it only becomes goals once folded into the round total.

---

## 9. Admin-only endpoints

Require a JWT whose `role` claim is `"admin"` (401 if unauthenticated, 403 if authenticated but
not an admin).

`GET /api/admin/fixtures/candidates?league=<optional>&from=<date>&to=<date>` — search upcoming
real-world fixtures to build the next round from:
```ts
type FixtureCandidateResponse = {
  externalMatchId: string;
  league: string;
  homeTeam: string;
  awayTeam: string;
  kickoffAt: string;
}[]
```

`POST /api/admin/matches` — create a round from exactly 9 selected fixtures:
```json
{
  "roundNumber": 6,
  "matches": [
    { "externalMatchId": "12345", "league": "Premier League", "homeTeam": "Arsenal", "awayTeam": "Chelsea", "kickoffAt": "2026-08-20T15:00:00Z" },
    /* ... exactly 9 total */
  ]
}
```
`400` if not exactly 9 matches. Returns `201` with the created `AdminMatchResponse[]`.

`PATCH /api/admin/matches/{id}/score` — manual override (alongside the automatic live sync):
```json
{ "homeScore": 2, "awayScore": 1, "status": "finished" }
```
`status` is optional (`"scheduled" | "live" | "finished"`) — omit it to only change the score.
Every call here is audit-logged server-side and immediately re-triggers scoring + a live
broadcast, so the effect is visible on `/ws/live` right away.

---

## 10. What's intentionally not here yet

- No endpoint to list "my predictions for a round" — track submissions client-side for now.
- No per-user privacy on `/api/users/{id}/stats`.
- No explicit logout/session-revocation endpoint — discard tokens client-side.
- No push notifications — the live experience is WebSocket-only.

Flag any of these to your backend contact if the frontend/mobile build actually needs them.
