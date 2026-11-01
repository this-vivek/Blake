# Blake — Progress Summary

Personal assistant app: Claude routes free text/photos to pluggable modules
(meals, workouts, expenses, tasks). Deployed and in daily use on Android.

## Architecture

- **Backend**: Python / FastAPI, deployed on Render (free tier), auto-redeploys on push to `main`.
- **Storage**: Postgres (Neon, free tier) via `DATABASE_URL`; falls back to SQLite for tests/local dev.
- **AI**: Anthropic API — Haiku for fast logging/parsing, Sonnet for coaching/review (per `CLAUDE.md`).
- **Client**: Native Android (Kotlin + Jetpack Compose). Built via GitHub Actions (cloud build, no local Android Studio/SDK needed) — debug APK downloaded as a workflow artifact and sideloaded.
- **Security**: `X-API-Key` header required on all data endpoints (`BLAKE_API_KEY`), separate from the server-side `ANTHROPIC_API_KEY`.

## Modules (backend)

| Module | Tools | Notes |
|---|---|---|
| `meals` | `log_meal`, `daily_summary`, `suggest_meal` | 113-food static nutrition table (deterministic, offline). Fuzzy word-matching with noise-word stripping; parenthetical ingredient asides are ignored to avoid false matches. Unmatched items are queued (`kind="unmatched"`) for manual curation. `suggest_meal` picks from curated combos per meal-type, excluding anything eaten in the last 2 days, rotating deterministically by day. |
| `workouts` | `log_set`, `suggest_routine` | PR detection via Epley 1RM formula, computed by the tool (never guessed). `suggest_routine` returns a fixed exercise plan from a static `(location, muscle_group)` table; supports multiple comma-separated muscle groups in one call. |
| `expenses` | `log_expense`, `daily_summary` | Category enum with fallback to "other"; daily total + per-category breakdown. |
| `tasks` | `add_task`, `complete_task`, `list_tasks` | Completion is a separate append-only event (`task_done`), not a mutation — open tasks are derived by diffing against completed IDs. |

All modules are auto-discovered by `app/core/registry.py` — adding a module requires no core changes.

## API surface

- `POST /chat` — free-text/photo entry point; routes through Claude's tool-use loop. Every turn (not just tool calls) is logged as a `chat`/`turn` event.
- `GET /history?module=&kind=&limit=` — raw event browsing per module.
- `GET /summary` — aggregates each module's `daily_summary` or `list_tasks` tool.
- `GET /suggest/workout?location=&muscle_group=` — deterministic, no LLM call.
- `GET /suggest/meal?meal=` — deterministic, no LLM call.
- `POST /log/meal` — logs a (possibly user-edited) suggestion directly, bypassing Claude entirely.

## Android app

Three tabs:
1. **Today** (default) — pick Gym/Home → muscle-group chips → instant exercise plan. Meal-type chips (defaulting to current local time) → suggested combo with editable gram fields → "Log this meal".
2. **Log** — free-text + optional photo chat box (the original logging flow), with a Settings dialog for server URL / API key.
3. **History** — today's summary (meals kcal, spend, open tasks) plus a browsable per-module event list.

## Real bugs found and fixed along the way

- Missing `python-multipart` dependency crashed the deployed container on startup (FastAPI's `Form`/`File` need it at runtime).
- Meal matching originally required exact string matches against a 6-item table — real phrasing like "boiled egg" or USDA-style names never matched. Fixed with noise-word stripping + word-subset matching, then expanded the table to 113 entries.
- Word-subset matching caused a **false positive**: "poha (cooked flattened rice with turmeric, onion, peanuts)" matched "onion" (a word mentioned in the parenthetical aside) instead of the actual dish. Fixed by stripping parenthetical text before matching — ingredient asides describe the dish, they aren't the dish.
- Render's free tier has no persistent disk — SQLite data reset on every restart. Fixed by adding an optional Postgres backend (Neon free tier), activated via `DATABASE_URL`, with SQLite remaining the default for offline tests.

## Testing

27 tests, all offline (fake Anthropic client, SQLite `:memory:` — no real API calls, no network). `evals/routing_cases.jsonl` has 24 routing cases for `make eval` (hits the real API, intentionally excluded from `make test`).

## Known limitations / open items

- Exercise split (`modules/workouts/__init__.py`) is a generic default, not the user's actual program — swap anytime.
- Word-subset food matching can still misfire on multi-ingredient dish names without parentheses (e.g. "egg curry with onion and tomato" could match the wrong ingredient if the dish name itself isn't in the table).
- Render free tier cold-starts (~5-30s) after ~15 min idle — Android client has a 40s timeout to absorb this, but first request after idle is slow.
- No notifications/reminders, no streaks/gamification, no multi-user support (single shared API key, single shared DB).
