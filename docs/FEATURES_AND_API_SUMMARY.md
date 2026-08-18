# Predict Rivals — Backend Features & API Summary

**Audience:** mobile (iOS/Android) and web client developers. This is a scannable overview —
for exact request/response JSON shapes, see [API.md](API.md). For the "why" behind any of this,
see the design docs: [backend design](superpowers/specs/2026-08-14-backend-design.md),
[multi-tournament design](superpowers/specs/2026-08-17-multi-tournament-design.md).

Base URL: wherever the backend is deployed (local dev: `http://localhost:8080`).

---

## 1. Feature overview

**Auth** — Email/password, Google Sign-In, Facebook Login. All converge on the same JWT
access/refresh token pair. (Phone/SMS and Apple Sign In were evaluated and intentionally not
built — both required ongoing paid third-party services.)

**Tournaments** — Any signed-in user can create a tournament and becomes its owner. Tournaments
are joined by a short shared code (not browsed from a public list), have a player cap (2–50) the
owner sets, and auto-start once that cap is reached — or the owner can start early. Only the
owner curates that tournament's matches. A user can belong to many tournaments at once.

**Match curation** — The tournament owner searches real-world football fixtures and picks
exactly 9 per round. Live scores sync automatically from an external provider every 5 minutes
while matches are in progress; the owner can also manually override a score.

**Predictions** — Players predict the score of each of the round's 9 matches. Editable up until
that match's kickoff; the same endpoint handles both first submission and edits.

**Scoring** — Automatic, per-prediction, the moment a match's score changes (live or manual).
Exact score = 3 points; correct result + correct goal difference = 2; correct result only, or a
correctly-guessed draw, = 1; wrong result = 0.

**Standings & top scorers** — Live leaderboard per tournament, always up to date, no manual
refresh/recalculation step needed.

**Live screen** — Real-time match scores + standings + the round-in-progress breakdown, pushed
over WebSocket as things change, or fetched as a one-shot snapshot over REST.

**Calendar** — Full past/upcoming round-and-match schedule for a tournament.

---

## 2. Full API list

All endpoints are `Content-Type: application/json` in and out (except the WebSocket, which
streams JSON text frames). 🔒 = requires `Authorization: Bearer <accessToken>`. 👑 = also
requires being that tournament's owner.

### Auth
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/email/register` | Create an account |
| POST | `/api/auth/email/login` | Sign in |
| POST | `/api/auth/google` | Sign in/up via Google ID token |
| POST | `/api/auth/facebook` | Sign in/up via Facebook access token |
| POST | `/api/auth/refresh` | Exchange a refresh token for a new access/refresh pair |

### Tournaments
| Method | Path | Purpose |
|---|---|---|
| 🔒 POST | `/api/tournaments` | Create a tournament (caller becomes owner + first player) |
| 🔒 POST | `/api/tournaments/join` | Join by code (auto-starts if this fills the cap) |
| 🔒 POST | `/api/tournaments/{id}/start` | 👑 Start early, below the player cap |
| 🔒 GET | `/api/tournaments/mine` | Tournaments you own or belong to |
| 🔒 GET | `/api/tournaments/{id}` | Tournament details (no membership required — preview before joining) |

### Match curation
| Method | Path | Purpose |
|---|---|---|
| 🔒 GET | `/api/fixtures/candidates` | Search real-world fixtures to build a round from (any signed-in user, not owner-restricted) |
| 🔒 POST | `/api/tournaments/{id}/matches` | 👑 Create a round from exactly 9 fixtures (tournament must be `active`) |
| 🔒 PATCH | `/api/tournaments/{id}/matches/{matchId}/score` | 👑 Manually override a match's score/status |

### Predictions
| Method | Path | Purpose |
|---|---|---|
| 🔒 POST | `/api/predictions` | Submit or edit a prediction for one match |

### Rounds & calendar
| Method | Path | Purpose |
|---|---|---|
| 🔒 GET | `/api/tournaments/{id}/rounds/current` | The round to show by default |
| 🔒 GET | `/api/tournaments/{id}/calendar` | Every round + its matches |

### Standings & stats
| Method | Path | Purpose |
|---|---|---|
| 🔒 GET | `/api/tournaments/{id}/standings` | Live leaderboard |
| 🔒 GET | `/api/tournaments/{id}/top-scorers` | Same ranking, "bombardier" framing |
| 🔒 GET | `/api/tournaments/{id}/users/{userId}/stats` | One player's numbers for this tournament |

### Live
| Method | Path | Purpose |
|---|---|---|
| 🔒 GET | `/api/tournaments/{id}/live` | One-shot snapshot: matches + standings + round-in-progress |
| — | `WS /ws/tournaments/{id}/live?token=<accessToken>` | Same payload, pushed on every change (see note below — auth is different here) |

All `{id}`/`{tournamentId}` routes above (except tournament creation/join/mine and the fixture
search) require the caller to be a **member** of that tournament — `403` otherwise.

---

## 3. Rules a client must handle itself

- **Tournament lifecycle:** matches/rounds can only be created once a tournament is `active`
  (cap reached, or owner called `/start`). Gate any "set up round 1" UI on `status`.
- **Prediction deadlines:** the backend accepts a late prediction write but silently excludes it
  from scoring — it will never error. **Disable the prediction form yourself** once a match's
  `kickoffAt` passes; don't rely on a server error to stop the user.
- **WebSocket auth is different from every other endpoint:** browsers can't attach a custom
  `Authorization` header to a WebSocket handshake, so the access token goes as a `?token=` query
  parameter instead. The server closes the connection immediately if it's missing/invalid/not a
  member.
- **Refresh tokens are single-use** — every `/api/auth/refresh` call rotates it; always store the
  new one from the response.
- **Extra time / penalties are never reflected anywhere** — a match's score locks in at 90
  minutes + stoppage regardless of what happens afterward in a cup tie.
- **Only one tournament format exists right now** (`solo_points` — independent predictions, no
  head-to-head). Round-robin/playoff formats are planned but not built; tournament creation
  doesn't expose a `format` choice yet for that reason.

---

## 4. Not built yet

- Round-robin / playoff tournament formats.
- Listing "my predictions for a round" (track submissions client-side for now).
- Owner ability to remove a player, change the cap, or transfer ownership after creation.
- Logout/session revocation beyond discarding tokens client-side.
- Push notifications (live updates are WebSocket-only).

See [API.md](API.md) for full request/response shapes and error codes.
