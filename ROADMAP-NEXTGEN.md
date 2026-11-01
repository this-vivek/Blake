# Blake: Next-Gen Roadmap

*For Claude Sonnet, working in Claude Code. This replaces `ROADMAP.md`, but that file's habit principles still apply: pull over push, streaks on outcomes with a grace day, no badges or points, at most 3 notifications a day. Read `CLAUDE.md` and `PROGRESS.md` before starting.*

---

## Fixed constraints

- **Single user.** No auth, accounts or multi-tenancy.
- **Free tier only.** Render free sleeps after about 15 minutes idle; the database is Neon free. No paid services, no FCM, and no server-side cron (a sleeping server can't run one).
- **Expenses stay in Blake.** No integration with other apps.
- **Locale.** Currency is ₹ with en-IN grouping, units are metric, and the user's timezone is `Asia/Kolkata`.

## Rules for every task

1. **Determinism.** Code computes every number the user sees: nutrition, 1RM, totals, streaks, trends and nudges. The model may only route, extract structured fields, narrate a stats packet that code has already computed, and *propose* (never save) food-table entries.
2. **One task per commit.** `make test` must pass on every commit. A phase isn't done until the Android CI build passes.
3. **Backend tests stay offline.** Use the fake Anthropic client and SQLite `:memory:`. Any logic that depends on time takes an injected clock.
4. **Schema changes are additive only.** Existing events must stay valid. Stop and ask before any migration that rewrites or drops data.
5. **Every new tool needs at least 3 cases** in `evals/routing_cases.jsonl`.
6. **Every new screen needs a screenshot test** in light and dark. After CI runs, download the artifacts with `gh run download` and view the PNGs to check your own work before marking the task done.
7. **Justify every new dependency** in one line in the commit. Already approved: Room, WorkManager, Navigation Compose, Glance, Roborazzi (or Paparazzi).
8. **Models.** Haiku handles routing and extraction. Sonnet is used only for the weekly review and food-entry proposals. Model IDs live in config. Cache the static system-plus-tools prompt prefix.
9. **Find the real file paths; don't assume them.** Update `PROGRESS.md` at the end of each phase.
10. **Stop and ask** if a task's done criteria can't be met or a task would need a paid service.

---

## UI audit (from current screenshots)

| Issue | Fix | Phase |
|---|---|---|
| Tabs sit at the top of the screen, out of thumb reach | Bottom `NavigationBar` | P1 |
| Not edge-to-edge (the status bar is grey) | Edge-to-edge (required when targeting SDK 35) | P1 |
| The workout plan is read-only text, with no way to go from plan to logging sets | Session mode | P4 |
| Today is one long scroll of chips and text with no visual hierarchy | Card dashboard | P3 |
| Raw numbers: `1576.0 kcal`, `Spent today: 0.0`, `296.7 kcal` | Shared formatter: whole-number kcal, `92 g`, `₹1,240` | P1 |
| The Log tab shows no reply and no record of what was logged; Settings is hidden inside it | One input bar for logging, plus a result card with Edit/Undo; Settings moves to the top bar | P1 |
| The History summary is crammed into one line, and a bare `Loading…` exposes the cold start | Show cached data with "updated Xm ago", then refresh | P2 |
| At 00:32 IST, History shows 1,576 kcal for "today" | Day boundaries are probably in UTC. Confirm this first | P0 |
| The meal chip defaults to breakfast at 00:32 | Late night should map to snack | P0 |

## Screen layout

- **Bottom nav:** Today · Timeline · Insights. Settings opens from a gear in the top bar.
- **Input bar:** "Log anything…" with a camera button (a mic comes later), docked on Today. It replaces the Log tab.
- **Full-screen flows:** Workout session, Meal edit, Unmatched foods, Goals, Settings.
- That makes 7+ destinations with a back stack, which passes `ROADMAP.md`'s "5+ screens" bar. Adopt Navigation Compose now.

```
TODAY                                  SESSION
┌────────────────────────────────┐    ┌────────────────────────────────┐
│ Mon, 2 Nov                  ⚙  │    │ ←  Push day · Gym     00:41  ⏱ │
│ [12-day logging] [3/4 this wk] │    │ Bench Press      last 60 × 8   │
├────────────────────────────────┤    │  1  [62.5] kg × [8]    ✓       │
│ NUTRITION                      │    │  2  [62.5] kg × [8]    ✓   PR  │
│ (ring)  1,240 / 2,200 kcal     │    │  3  [62.5] kg × [ ]    ○       │
│ Protein ███████░░░  92 / 140 g │    │  + set                         │
│ [B ✓] [L ✓] [D +] [S +]        │    │  Rest 1:24                     │
│ Usual dinner: 3 roti + dal [+] │    ├────────────────────────────────┤
├────────────────────────────────┤    │ Incline DB Press last 22 × 10  │
│ TRAINING · Push day            │    │  1  [24] kg × [10]     ○       │
│ 6 exercises     [Start session]│    │  ...                           │
├────────────────────────────────┤    │                                │
│ SPEND        ₹420 / ₹800 today │    │ [        Finish session      ] │
├────────────────────────────────┤    └────────────────────────────────┘
│ TASKS   ☐ Renew insurance      │
├────────────────────────────────┤
│ 48 g protein short: 200 g      │
│ paneer closes it               │
├────────────────────────────────┤
│ [cam]  Log anything…       [>] │
├────────────────────────────────┤
│  Today    Timeline    Insights │
└────────────────────────────────┘
          (numbers illustrative)
```

**Visual direction**
- Material 3 with dynamic color on Android 12+, using the current purple as the fallback seed. Full dark theme.
- Lead with the data: one large number per card. Draw rings, bars and sparklines with Compose `Canvas` rather than a chart library.
- Tonal cards on a 16dp grid, one primary action per card, and a haptic tick when a log succeeds.
- Never show a bare spinner. Show cached data first, then refresh.

---

## Phases

Plan on one phase per Claude Code session. P1 is the first visible upgrade; P3 is where the app starts to feel next-gen.

### P0: Foundations (backend and build; everything else depends on these)

| # | Task | Done when |
|---|---|---|
| 0.1 | Compute day boundaries in the user's timezone (`BLAKE_TZ`, default `Asia/Kolkata`). Store timestamps in UTC; group them by day in that timezone. Change the time-based meal default so late night maps to snack. | Reproduce the suspected bug in a test first. Then: a meal at 00:30 IST counts toward that IST day. |
| 0.2 | **Corrections.** Add a `void` event that points to a `target_id`. Every aggregation reads through one core helper that skips voided events. Add `POST /events/{id}/void` and a `void_event` tool. An edit is a void plus a fresh log. | A voided meal, set or expense disappears from every summary, with one test per module. |
| 0.3 | **Idempotent writes.** Add an optional client UUID to every `POST /log/*`, backed by a unique constraint. A duplicate returns the original event. | Sending the same request twice creates one event. |
| 0.4 | **`goals` module** with kcal, protein_g, daily_spend and workout_days_per_week; the latest value wins. Add tools `set_goal` and `get_goals`, plus `GET` and `PUT /goals`. | Routing evals pass for phrases like "set protein target to 140g". |
| 0.5 | Direct endpoints that skip the LLM: `POST /log/set` and `POST /tasks/{id}/complete`. | They behave like `/log/meal`. |
| 0.6 | **`GET /today`**, one payload covering: kcal and protein vs goals, which meal slots are logged, today's sets, muscle groups and PRs, spend vs budget, open tasks, and `nudges[]` (rule-based). | A snapshot test with a fixed clock passes. |
| 0.7 | Make sure photos aren't stored as base64 inside events, given Neon's free storage limit. Keep only the parsed output. | Confirmed, or fixed. |
| 0.8 | **Android: a stable signing key** stored in GitHub secrets. A fresh CI debug keystore on each run means one APK can't install over the last. You have to uninstall, which will wipe local data once P2 lands. | Two consecutive CI APKs install over each other. |

### P1: App shell and design system (no new features)

- Go edge-to-edge. Build the M3 theme: dynamic color with the fallback seed, dark mode, and spacing and type tokens.
- Add Navigation Compose with the bottom nav (Today · Timeline · Insights); Insights can be a placeholder for now. Add a Settings screen (server URL, API key, goals), opened from the top bar.
- Add a `Format` utility (kcal, grams, ₹ with en-IN grouping) and route every number on screen through it.
- Build the input bar (text plus photo) and dock it on Today, then remove the Log tab. Each reply shows as a **result card**: what was logged, its values, and Edit/Undo (using 0.2).
- **Timeline** replaces History: a feed grouped by day, with filter chips per module. Tapping an entry gives Edit/Undo.
- Set up Roborazzi in CI so it uploads PNGs as artifacts.

**Done when:** every current feature is still reachable, and the screenshots have been checked in light and dark.

### P2: Offline-first data layer

- Room tables: the cached `/today` payload, recent events, goals, and an `outbox`.
- The UI reads **only** from Room Flows. Network refreshes write into Room, so cached data shows first and refreshes behind it. Show "updated Xm ago".
- Every direct write goes into the outbox and updates the UI immediately. A WorkManager job syncs it with backoff, using the client UUID from 0.3. Chat and photo entries wait in the queue marked "pending parse".
- When the app comes to the foreground, send `GET /health` without waiting for the response. That starts the cold boot while cached data is already on screen.
- Optional, off by default: an external keep-alive ping. Only add it after checking Render's free-tier hours and terms.

**Done when:** in airplane mode you can log a meal and a set, both show up instantly, and each syncs **exactly once** on reconnect.

### P3: A Today screen that tells you something

Card order: streak chips → **Nutrition** (kcal ring, protein bar, B/L/D/S slot row with ✓/+, a one-tap "Usual" chip) → **Training** (today's program day with Start session, or a summary if done) → **Spend** (vs daily budget, quick-add) → **Tasks** (complete with a checkbox) → at most 2 nudges → input bar.

- "Usual" is the most frequent combo for that slot over the last 30 days. The server computes it and the app caches it.
- Nudge rules, all deterministic:
  - A slot is still unlogged past its usual time.
  - Protein is short in the evening. Show 2–3 of your usual foods that would close the gap, using values from the food table.
  - Spending is running over budget.

**Done when:** Today opens on cached data in under 1 second and tells you something before you tap anything.

### P4: Workout session mode

- **Program.** Replace the static split with a program you can edit: an ordered list of days, each with exercises, sets and reps (plus Gym/Home variants), stored as events. The default is the current table. Today's day is the one after the last completed day, and rest days are supported. Edit it by chat (`set_program`) or on a simple editor screen.
- **Session screen.**
  - Exercise cards with set rows prefilled from last session's weight × reps, adjusted with steppers.
  - Tapping ✓ logs the set through the outbox.
  - A rest timer.
  - A PR badge that appears after sync. The server stays the only place Epley is calculated, so the formula never exists in two places.
- **Progressive overload, deterministic.** If every rep was hit at weight W last time, suggest W plus an increment (default 2.5 kg barbell, 2 kg dumbbell, configurable). Otherwise repeat W.
- **Summary:** duration, volume per muscle group, PRs.

**Done when:** a full session can be logged without typing anything.

### P5: Faster, more accurate meal logging

- Claude extracts `{food, qty, unit}`. Code converts that to grams using a **household-units table** (piece, katori, cup, roti…) with defaults you can edit.
- **Recipes:** a composite dish is defined by its ingredients, and code computes its per-100 g values. You can define your own versions ("my poha").
- **Matching:** check the dish name first, and the longest specific match wins. Add regression cases, including "egg curry with onion and tomato".
- Add a `source` column to the food table. Add common Indian dishes, recording a source for each row (e.g. IFCT 2017, USDA FoodData Central).
- **Unmatched screen:** map an item to an existing food or create a new one. Sonnet may *propose* values, flagged `proposed`; nothing is stored until you approve it.
- **Photo flow:** save immediately and show a result card with editable grams and Undo. No confirmation step that blocks you.

**Done when:** the share of unmatched items in the last 30 days of real logs drops. Report the before and after numbers.

### P6: Insights and weekly coaching

- `GET /trends?metric=&days=` covering kcal, protein, spend, workout days, volume per muscle group, and how often you hit each goal (%).
- **Insights tab:** 14- and 30-day sparklines with goal lines.
- **Streaks**, computed on the server and unit-tested, including timezone edge cases:
  - meals logged (daily)
  - protein target hit (daily)
  - training (**weekly**: sessions per week vs goal, so rest days don't break it)
  - Daily streaks get 1 grace day a week.
- **Weekly review.** Code builds the stats packet; Sonnet writes up to 120 words and up to 3 focus items, saved as an event. It's generated **on demand**, on the first request after the week closes, so no cron is needed. Test that every number in the write-up appears in the packet.
- **Chat context.** Add the last few turns and a snapshot of today to the prompt, so a follow-up like "make that 3 rotis" works (as a void plus a fresh log).

### P7: Reminders and home-screen widget

- **Local reminders** via WorkManager, inexact, so no exact-alarm permission is needed.
  - A reminder fires only if that slot is still unlogged in Room.
  - Its time is the median of your past log times for that slot, and you can override it.
  - At most 3 a day.
  - Ask for the POST_NOTIFICATIONS permission (Android 13+).
- A notification action, **"Log usual"**, writes straight to the outbox without opening the app.
- **Glance widget:** kcal and protein ring, "Log usual <slot>", and a "Start session" deep link. It reads from Room and refreshes after each sync.

### P8: New modules

- **Habits:** generic yes/no or count habits with grace streaks. Water tracking lives here.
- **Body weight:** trend line with a 7-day moving average.
- **Sleep:** manual duration plus quality.

Each module gets its tools, a Today card, an Insights metric, and routing evals.

### P9: Polish

- Voice input (Android `SpeechRecognizer` feeding the input bar).
- `GET /export` (JSON/CSV) with an export option in the app.
- An update check against GitHub Releases.
- Due dates on tasks, feeding into reminders.

---

## Out of scope

Multi-user or auth, FCM or a push backend, wearables, paid hosting, badges or points, mood journaling, integrations with outside expense apps.
