# Predict Rivals — Backend

Kotlin + Ktor backend for Predict Rivals — user-created, joinable football prediction
tournaments. See
[docs/superpowers/specs/2026-08-14-backend-design.md](docs/superpowers/specs/2026-08-14-backend-design.md)
for the original design (architecture, data model, scoring rules, security) and
[docs/superpowers/specs/2026-08-17-multi-tournament-design.md](docs/superpowers/specs/2026-08-17-multi-tournament-design.md)
for the multi-tournament rework, or [docs/API.md](docs/API.md) for the current API reference.

## Prerequisites

- JDK 21+
- Docker (for local Postgres)

## Local setup

1. Start Postgres:

   ```bash
   docker compose up -d
   ```

2. Copy the env template and fill in real values (at minimum `JWT_SECRET`; other
   provider keys can stay as placeholders until you wire up that provider):

   ```bash
   cp .env.example .env
   ```

3. Export the env vars and run the app (Flyway migrations run automatically on startup):

   ```bash
   set -a && source .env && set +a
   ./gradlew run
   ```

   The server listens on `http://localhost:8080` (or `$PORT` if set).

## Tests

```bash
./gradlew test
```

Unit tests (e.g. `ScoringEngineTest`) run with no external dependencies. Repository/integration
tests use Testcontainers, which requires Docker to be running.

## Project layout

```
src/main/kotlin/com/predictrivals/
  auth/          # AuthProvider + email/Google/Facebook providers, JWT issuance
  tournament/     # User-created tournaments, join-by-code, capacity/start lifecycle
  rounds/         # Round lifecycle, calendar
  adminMatches/   # Per-tournament owner-curated matches, fixture search
  predictions/    # Submit/update predictions
  scoring/        # Scoring engine + round-level goal conversion
  standings/      # Leaderboard, top scorers, user stats
  footballApi/    # API-Football adapter + request budget tracking
  liveSync/       # Background live-score polling worker
  live/           # WebSocket broadcast hub + /api/live
  plugins/        # Ktor setup (auth, CORS, error handling, rate limiting, serialization)
  common/         # Config, DB, JWT, hashing, shared exceptions
```

## Deployment

Deploys to Railway as a JVM app (`./gradlew run` or a built jar) with a Postgres add-on. Set all
variables from `.env.example` in Railway's environment variable store — none of them belong in
source control.
