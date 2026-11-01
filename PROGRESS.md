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
| `meals` | `log_meal`, `daily_summary`, `suggest_meal` | 113-food static nutrition table (deterministic, offline). Fuzzy word-matching with noise-word stripping; parenthetical ingredient asides are ignored to avoid false matches. Unmatched items are queued (`kind="unmatched"`) for manual curation. `suggest_meal` picks from curated combos per meal-type, excluding anything eaten in the last 2 days, rotating deterministically by local day. `daily_summary` is a local-calendar-day boundary (not rolling 24h). |
| `workouts` | `log_set`, `suggest_routine` | PR detection via Epley 1RM formula, computed by the tool (never guessed). `suggest_routine` returns a fixed exercise plan from a static `(location, muscle_group)` table; supports multiple comma-separated muscle groups in one call. `muscle_groups_for()`/`prs_in()` helpers support `/today`. |
| `expenses` | `log_expense`, `daily_summary` | Category enum with fallback to "other"; daily total + per-category breakdown; local-calendar-day boundary. |
| `tasks` | `add_task`, `complete_task`, `complete_by_id`, `list_tasks` | Completion is a separate append-only event (`task_done`), not a mutation — open tasks are derived by diffing against completed IDs. |
| `goals` | `set_goal`, `get_goals` | kcal/protein_g/daily_spend/workout_days_per_week. Partial updates — latest value per field wins, merged across history. |
| `events` | `void_event` | Undo/correct any event by id (`POST /events/{id}/void`). A correction is void + a fresh log, never a mutation or delete — keeps the event log truly append-only. |

All modules are auto-discovered by `app/core/registry.py` — adding a module requires no core changes.

`app/core/clock.py`: single source of "now", `BLAKE_TZ` (default `Asia/Kolkata`), `day_start()` for local-calendar-day boundaries, injectable for deterministic tests.

`app/core/db.py`: `query_active()` is the one helper every aggregation reads through to respect voided events. `append(client_id=...)` makes writes idempotent (a retry with the same client_id returns the original event instead of duplicating).

`app/today.py`: cross-module aggregation (not a module itself — reads across meals/workouts/expenses/goals/tasks) backing `GET /today`.

## API surface

- `POST /chat` — free-text/photo entry point; routes through Claude's tool-use loop. Every turn (not just tool calls) is logged as a `chat`/`turn` event.
- `GET /history?module=&kind=&limit=` — raw event browsing per module (includes void events, for audit visibility).
- `GET /summary` — aggregates each module's `daily_summary` or `list_tasks` tool.
- `GET /today` — one payload: kcal/protein vs goals, meal slots logged today, today's sets/muscle groups/PRs, spend vs budget, open tasks, up to 2 rule-based nudges.
- `GET /suggest/workout?location=&muscle_group=` — deterministic, no LLM call.
- `GET /suggest/meal?meal=` — deterministic, no LLM call.
- `POST /log/meal`, `POST /log/set` — log directly, bypassing Claude; both accept an optional `client_id` for idempotent retries.
- `POST /tasks/{id}/complete` — complete by numeric id (vs. `complete_task`'s fuzzy title match).
- `POST /events/{id}/void` — undo/correct any event.
- `GET`/`PUT /goals` — read/update goals (partial updates supported).

## Android app

Bottom nav: **Today** / **Timeline** / **Insights**, gear icon in the top bar → **Settings**.

1. **Today** (default/start destination) — Gym/Home → muscle-group chips → instant exercise plan; meal-type chips (defaulting to current local time) → suggested combo with editable gram fields → "Log this meal"; docked input bar at the bottom ("Log anything...", text + photo) replacing the old standalone Log tab — each reply renders as a result card with Edit (void + repopulate input) / Undo per logged event.
2. **Timeline** (replaces History) — today's summary line, filter chips per module, feed grouped by day ("Today"/"Yesterday"/date). Tapping an entry expands an Undo action; voided entries stay visible with strikethrough rather than disappearing.
3. **Insights** — placeholder (real content is P6).
4. **Settings** — server URL / API key, plus goals (kcal, protein_g, daily_spend, workout_days_per_week) wired to `GET`/`PUT /goals`.

M3 theme with dynamic color (Android 12+) + dark mode (`BlakeTheme`), edge-to-edge, `Format` utility (whole-number kcal, "92 g", "₹1,240" en-IN grouping) applied to every displayed number.

## Real bugs found and fixed along the way

- Missing `python-multipart` dependency crashed the deployed container on startup (FastAPI's `Form`/`File` need it at runtime).
- Meal matching originally required exact string matches against a 6-item table — real phrasing like "boiled egg" or USDA-style names never matched. Fixed with noise-word stripping + word-subset matching, then expanded the table to 113 entries.
- Word-subset matching caused a **false positive**: "poha (cooked flattened rice with turmeric, onion, peanuts)" matched "onion" (a word mentioned in the parenthetical aside) instead of the actual dish. Fixed by stripping parenthetical text before matching — ingredient asides describe the dish, they aren't the dish.
- Render's free tier has no persistent disk — SQLite data reset on every restart. Fixed by adding an optional Postgres backend (Neon free tier), activated via `DATABASE_URL`, with SQLite remaining the default for offline tests.
- The app held one Postgres connection for its entire process lifetime; Neon's autosuspend (or Render's idle cycling) silently killed it, and psycopg2 never reconnects. Every DB read/write failed with "connection already closed" until restart. Fixed: open and close a fresh connection per operation.
- `daily_summary` used a rolling 24h window instead of a calendar-day boundary, so "today" could include food/spend from the previous evening (e.g. at 00:32 IST, a 9pm dinner was still inside the window). Fixed with `app/core/clock.py` and `BLAKE_TZ`.

## P0 (ROADMAP-NEXTGEN.md foundations) — complete

All 8 tasks done: day-boundary fix, void/correction events, idempotent writes (`client_id`), `goals` module, direct log endpoints (`/log/set`, `/tasks/{id}/complete`), `GET /today` aggregate, photo-storage audit (confirmed clean, no fix needed), and a stable Android CI signing key (committed PKCS12 debug keystore so consecutive CI-built APKs install over each other without uninstalling).

## P1 (app shell/design system) — complete, one caveat

All 7 tasks done: M3 dynamic-color theme + dark mode + edge-to-edge, `Format` utility, Navigation Compose bottom-nav shell, Settings as its own screen (incl. goals), input bar docked on Today with result-card Edit/Undo, Timeline replacing History (day-grouped, tap-to-Undo), Roborazzi wired into CI.

**Caveat on the last one:** Roborazzi's screenshot tests run and pass (8/8, confirmed via downloaded test-reports — not just CI's own "success" status) but produce zero actual PNG files, with two different invocation strategies (`-Proborazzi.test.record=true` and the dedicated `recordRoborazziDebug` task) producing byte-for-byte identical behavior. Likely cause: Robolectric's `NATIVE` graphics mode needs native rendering libraries that may be missing on the default `ubuntu-latest` runner, and some Roborazzi versions skip writing silently rather than failing when rendering can't actually produce pixels. Diagnosing further needs either raw system-level CI logs (blocked — downloading them requires repo-admin auth this session doesn't have) or trial-and-error with native packages. Scaffolding is left in place (harmless, tests pass, APK build unaffected); worth revisiting with log access or by trying Paparazzi instead (the roadmap's named alternative).

Next up per `ROADMAP-NEXTGEN.md`: P2 (offline-first data layer — Room, WorkManager outbox, cached-first UI).

## Testing

65 backend tests, all offline (fake Anthropic client, SQLite `:memory:`/injected clock — no real API calls, no network, no wall-clock dependency). `evals/routing_cases.jsonl` has 31 routing cases for `make eval` (hits the real API, intentionally excluded from `make test`). Android: 8 Roborazzi screenshot tests (pass, but currently produce no viewable output — see P1 caveat above).

## Known limitations / open items

- Exercise split (`modules/workouts/__init__.py`) is a generic default, not the user's actual program — swap anytime (becomes editable in P4).
- Word-subset food matching can still misfire on multi-ingredient dish names without parentheses (e.g. "egg curry with onion and tomato" could match the wrong ingredient if the dish name itself isn't in the table) — targeted for improvement in P5.
- Render free tier cold-starts (~5-30s) after ~15 min idle — Android client has a 40s timeout to absorb this, but first request after idle is slow.
- No notifications/reminders, no streaks/gamification, no multi-user support (single shared API key, single shared DB) — explicitly out of scope per `ROADMAP-NEXTGEN.md`'s fixed constraints, except reminders/widget (P7) and habits/streaks (P8).
- Everything is online-only right now — no Room cache, no outbox, no offline logging. That's P2.
- Roborazzi screenshot tests produce no viewable PNGs yet (see P1 caveat above).
