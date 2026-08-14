# Deploying the backend to Railway

Step-by-step, for a first-time deploy. Assumes you already have a Railway account
(https://railway.app) and this repo pushed to GitHub.

---

## 1. Push the code to GitHub

Railway deploys from a GitHub repo. If this repo isn't on GitHub yet, create one and push it
(replace with your actual remote):

```bash
git remote add origin git@github.com:<you>/predict-rivals-backend.git
git push -u origin master
```

Make sure `Dockerfile` and `.dockerignore` (added alongside this guide) are committed — Railway
uses the `Dockerfile` to build the app, since a plain Gradle/Kotlin project isn't reliably
auto-detected otherwise.

---

## 2. Create the Railway project

1. Go to https://railway.app/new
2. Choose **"Deploy from GitHub repo"**, authorize Railway's GitHub app if prompted, and select
   this repo.
3. Railway creates a project with one service (the backend). Don't worry about it failing to
   build yet — it needs the database and env vars first.

---

## 3. Add PostgreSQL

1. In the project canvas, click **"+ New"** → **"Database"** → **"Add PostgreSQL"**.
2. Railway provisions a Postgres instance as its own service (default name **"Postgres"**) and
   exposes connection details as variables on that service: `PGHOST`, `PGPORT`, `PGUSER`,
   `PGPASSWORD`, `PGDATABASE` (plus a ready-made `DATABASE_URL` in `postgres://...` form — the
   app needs a `jdbc:postgresql://...` URL instead, so build it from the pieces in the next step
   rather than using Railway's `DATABASE_URL` directly).

   You can see these values yourself by clicking the **Postgres** service box → **Variables** tab
   (values are masked — click the eye icon to reveal one). You won't need to copy them anywhere,
   though: step 5 references them live from the backend service instead of pasting literal
   values. The only thing that has to match exactly is the **service name** shown at the top of
   this Postgres service's page — if Railway named it something other than "Postgres" (e.g.
   "Postgres-1"), use that exact name in the `${{...}}` references in step 5.

---

## 4. Configure the backend service

Click into the backend service (not the Postgres one) → **Settings**:

- **Build**: under "Build", set **Builder** to **Dockerfile** if it isn't already selected
  (Railway usually detects the `Dockerfile` automatically).
- **Networking**: under "Networking", click **Generate Domain** to get a public
  `https://<something>.up.railway.app` URL. Railway auto-injects a `PORT` env var and the app
  already reads it (`Application.kt` binds to `$PORT`, defaulting to 8080) — no config needed
  there.

---

## 5. Set environment variables

Still on the backend service, go to **Variables** and add everything from
[.env.example](../.env.example). Two of them are special:

**Database connection** — reference the Postgres service's own variables instead of typing
literal values (adjust `Postgres` in `${{...}}` if you renamed that service):

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DATABASE_USER` | `${{Postgres.PGUSER}}` |
| `DATABASE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |

**Everything else** — real values for whichever providers you're actually wiring up now (a
placeholder is fine for ones you haven't set up yet — that provider just won't work until you
fill it in, the app still boots):

```
JWT_SECRET=<generate a long random string, e.g. `openssl rand -base64 48`>
JWT_ISSUER=predict-rivals
JWT_AUDIENCE=predict-rivals-clients
JWT_ACCESS_TOKEN_TTL_MINUTES=30
JWT_REFRESH_TOKEN_TTL_DAYS=30

ALLOWED_ORIGINS=<your frontend's real origin(s), comma-separated, no trailing slash>

API_FOOTBALL_KEY=<from api-sports.io>
API_FOOTBALL_BASE_URL=https://v3.football.api-sports.io

GOOGLE_CLIENT_ID=<from Google Cloud Console OAuth client>
FACEBOOK_APP_ID=<from Meta for Developers>
FACEBOOK_APP_SECRET=<from Meta for Developers>
```

`JWT_SECRET` is the one you cannot skip — auth won't work without it. `ALLOWED_ORIGINS` matters
too: if it doesn't match your frontend's real domain, the browser will block every request with a
CORS error even though the API itself is fine.

---

## 6. Deploy

Setting the variables triggers a redeploy automatically. Otherwise, click **Deploy** in the top
right of the service view. Watch it under **Deployments** → the latest deployment → **View Logs**.

A successful boot looks like Flyway applying migrations (`V1__init_schemas` through
`V6__admin_audit_log`) followed by Ktor's own startup log line (something like
`Responding at http://0.0.0.0:8080`). If it crashes instead, the logs will show which env var or
DB connection failed — 90% of first-deploy failures are a missing/misreferenced variable from
step 5.

---

## 7. Seed a tournament

There's no admin endpoint for creating the tournament itself (the app assumes one tournament is
active at a time — see the design doc) — you create that one row directly in Postgres. In
Railway, click the **Postgres** service → **Data** tab (or **Connect** → copy the psql command
and run it locally), and run:

```sql
INSERT INTO game.tournaments (name, season, start_date, end_date)
VALUES ('Predict Rivals 2026', '2026', '2026-01-01', '2026-12-31');
```

Without this row, every endpoint that depends on "the active tournament" (`/api/rounds/current`,
`/api/tournament/join`, `/api/standings`, predictions, admin match creation) returns
`404 No active tournament`.

---

## 8. Smoke test

Once the tournament row exists, hit the public URL from step 4:

```bash
curl -X POST https://<your-app>.up.railway.app/api/auth/email/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User","email":"test@example.com","password":"testpass123"}'
```

A `201` with an `accessToken`/`refreshToken`/`user` back confirms the app, the database
connection, and the migrations are all working end-to-end. From there:

```bash
curl https://<your-app>.up.railway.app/api/rounds/current
```

returns `404 No rounds found` until an admin actually creates a round via
`POST /api/admin/matches` (see [docs/API.md](API.md) section 9) — that's expected, not a failure.

---

## 9. Redeploying later

Every push to the connected branch (default `master`) triggers an automatic redeploy — nothing
else to do. Flyway migrations are idempotent and run on every boot, so new migration files you
add later apply themselves the next time the service starts.

---

## Alternative: Railway CLI

If you'd rather not connect GitHub, you can push directly from your machine instead:

```bash
npm install -g @railway/cli
railway login
railway link          # choose the project you created in step 2
railway up            # builds the Dockerfile and deploys
```

Steps 3–8 above are identical either way — only how the code gets to Railway changes.
