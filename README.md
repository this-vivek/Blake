# Blake

A personal assistant for a daily habit: log what you eat, lift, spend and need to do, in plain text or with a photo, and get one clear view of today.

Claude reads free text and photos and routes them to small, pluggable modules (meals, workouts, expenses, tasks, goals). Claude extracts structure; **every number comes from deterministic code** (macros from a food table, PRs from the Epley formula, kcal burned from a MET table), never from a model guess.

- **Backend:** Python 3.12, FastAPI, an append-only event log (SQLite locally, Postgres in production).
- **Android app:** Kotlin and Jetpack Compose, dark-first, offline-first (Room cache and a WorkManager outbox that syncs each write exactly once).
- **Models:** logging and parsing use Haiku (`BLAKE_FAST_MODEL`), coaching and review use Sonnet (`BLAKE_SMART_MODEL`).

## What the app does

- **Today:** a kcal ring that collapses into the top bar as you scroll, one "Log <meal>" action chosen by the time of day (with your usual combo), protein progress, and Training, Spend and Tasks cards.
- **Log anything (the `+` button):** text, camera or gallery photo. Everything is saved immediately; the result stays below with editable grams, Edit and Undo.
- **Workout session:** starts from your weekly program, always with a warm-up. Large weight and reps steppers, suggested weight from progressive overload, rest timer, elapsed time and kcal burned. Sets are saved as you log them, so closing the app mid-session loses nothing. Starting a session can open YouTube Music.
- **Spend:** amount, description, category, and where it was spent (location is optional).
- **Timeline:** one expandable card per day with totals, filters by type, struck-through undone entries.
- **Unknown foods:** the Unmatched screen looks up per-100 g values (USDA FoodData Central first, otherwise a clearly labelled AI estimate), shows where they came from, and saves them only when you approve.
- **Settings:** server connection, goals, music playlist link, and data resets (reset today, reset all logs).

## Repository layout

```
app/            FastAPI app, the event log (core/), the Claude router, /today aggregate, resets
modules/<name>/ one folder per module; each exports `module: Module` (tools + system prompt)
tests/          offline tests (fake Anthropic client, in-memory SQLite)
evals/          routing and accuracy evals that call the real API (never part of `make test`)
android/        the Android app
ROADMAP*.md     product roadmap
PROGRESS.md     what has been built so far
CLAUDE.md       rules for working in this repo
```

Rules the code follows (see `CLAUDE.md`): a new feature is a new module or a new tool; tool names are `<module>__<tool>`; handlers are `(args: dict, db: Db) -> dict`, deterministic, validate input and return `{"error": ...}` instead of raising; state is persisted with `db.append(module, kind, payload)`; corrections and resets are `void` events, never deletes.

## Run it

```bash
make install        # pip install -e ".[dev]"
make test           # offline, no API key needed
make run            # uvicorn app.main:app --reload
make eval           # needs ANTHROPIC_API_KEY, hits the real API
```

### Configuration (environment variables)

| Variable | Purpose |
| --- | --- |
| `ANTHROPIC_API_KEY` | Claude API key (required to run the server) |
| `BLAKE_API_KEY` | Shared secret the app sends as `X-API-Key`; unset means no auth |
| `BLAKE_DB_PATH` | SQLite file (default `blake.db`) |
| `DATABASE_URL` | Postgres URL; when set it replaces SQLite (e.g. Neon) |
| `BLAKE_TZ` | Timezone for "today" (default `Asia/Kolkata`) |
| `USDA_API_KEY` | Free key for USDA FoodData Central lookups ([sign up](https://fdc.nal.usda.gov/api-key-signup.html)); without it the shared `DEMO_KEY` is used and is rate-limited |
| `BLAKE_FAST_MODEL` / `BLAKE_SMART_MODEL` | Model overrides |

## API

All endpoints except `/health` need the `X-API-Key` header when `BLAKE_API_KEY` is set.

| Area | Endpoints |
| --- | --- |
| Health, chat | `GET /health`, `POST /chat` (text and optional image) |
| Today | `GET /today`, `GET /summary`, `GET /history?module=` |
| Meals | `POST /log/meal`, `POST /meals/{id}/edit`, `GET /suggest/meal`, `GET /suggest/meal/refresh`, `GET /meals/unmatched`, `POST /meals/propose` (look up values for an unknown food, stores nothing), `POST /meals/custom_food` (approve and save, with its source), `POST /meals/unmatched/resolve` |
| Workouts | `POST /log/set`, `GET /suggest/workout`, `GET /suggest/next_set`, `GET /program/today`, `POST /program/complete` |
| Spend and tasks | `POST /log/expense` (amount, category, note, optional lat/lon/accuracy), `POST /tasks/{id}/complete` |
| Goals | `GET /goals`, `PUT /goals` |
| Corrections | `POST /events/{id}/void`, `POST /reset/day`, `POST /reset/logs` (both need `{"confirm": true}`) |

Writes accept an optional `client_id`; retrying with the same one returns the original result instead of logging twice.

## Deploy

The server runs on Render's free tier from the `Dockerfile` (see `render.yaml`) with Postgres on Neon. The free tier sleeps after about 15 minutes idle, so the first request after a break can take 5 to 30 seconds; the app pings `/health` when it comes to the foreground to hide that.

## Android app

Open `android/` in Android Studio, or let CI build it: every push that touches `android/` runs the **Android Build** workflow and uploads `blake-debug-apk`. The debug keystore is committed, so each new APK installs over the last one without uninstalling.

First run: open **Settings**, set the server URL and API key, save, and set your goals.

The look comes from the Blake Design System: near-black surfaces, one soft-gold accent, Sora for figures and Manrope for text, thin outline icons, and no dynamic wallpaper colour.

## Known limits

- Some home exercises (burpees, jumping jacks, high knees, pike push-ups, wall sit and a few others) have no photo yet and show a placeholder.
- Apps cannot start playback inside YouTube Music directly. The app opens it (or your playlist link) and sends a play command, which works when YouTube Music was your most recent player; otherwise tap play.
- Reset actions undo log entries; they keep goals, the program, custom foods and recipes.
- Single user: one shared API key and one database.
- Screenshot tests run in CI but do not yet produce viewable images.
