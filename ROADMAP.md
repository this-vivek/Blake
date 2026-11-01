# Blake: Roadmap to a Daily-Use Habit Assistant

*Design pass by Fable. See `PROGRESS.md` for what's already built.*

## 1. Habit-formation design: what makes someone open this every day

The core insight: **Blake already has the hard part** — logging is fast and frictionless because Claude does the parsing. The missing piece isn't more features, it's *pull*. Right now Blake is pure push (you remember, you open it, you log). Habit formation needs a trigger-action-reward loop, and today only "action" exists.

**Build:**
- **A living "Today" dashboard, not a form.** Right now Today is "pick inputs, get output." It should instead open already populated with state: "You haven't logged lunch yet — it's 1:40pm" / "3 sets into today's PR streak" / "$38 left in your daily spend pace." The screen should *tell you something about your day* before you touch anything.
- **One well-timed reminder per logging moment, not a reminder system.** A single local notification near your typical lunch/dinner time and a single post-usual-workout-window nudge ("Train today?") beats a notification settings page. Keep it to 2-3 total touchpoints a day — anything more trains you to dismiss.
- **Streaks, but of the right thing.** Don't streak "opened the app." Streak "logged at least one meal" and "hit protein target" separately — streaks on outcomes you actually care about, not engagement theater. Allow a once-a-week "grace" skip so one missed day doesn't nuke months of history — unforgiving streaks are the single biggest known driver of habit *abandonment*, not formation.
- **Visual trendlines over snapshots.** A 14-day sparkline of protein, workout volume per muscle group, and daily spend turns Blake from a logger into a mirror. This is the actual retention driver for a one-person power user: seeing *your own trend* is more motivating than any badge.

**Avoid (traps):** badges/achievements (no social context to show them off to, so they're dead weight), points/leveling (nothing to spend them on), anxiety-inducing streak counters with no grace, and over-notifying. For a single user with real intrinsic motivation (you built this for yourself), extrinsic gamification mostly adds maintenance cost for no behavioral return.

## 2. Android UX evolution

- **Today tab → status-first, action-second.** Top: a 3-line "state of your day" summary (meals logged / workout done / spend so far). Below: the existing quick-pick flows, demoted but intact.
- **One-tap repeat logging.** Track "usual" meals per time-of-day (same approach as `suggest_meal`'s recency logic, inverted: surface what you *most commonly* eat at this hour) and expose it as a single chip: "Same as yesterday (eggs + toast)?" → tap → logged. This is higher leverage than voice input because it removes typing entirely for the 70% case.
- **Notifications via local scheduling, not FCM.** You're single-user with no server-push need — Android's `AlarmManager`/`WorkManager` local notifications are sufficient and avoid building push infra, token management, or a notification backend. Reserve FCM only if Blake ever becomes multi-device (e.g., you + phone + watch).
- **Home-screen widget** showing today's macro ring and "log workout" shortcut — this is the single highest-leverage UI investment, since it removes the "open app" step entirely and turns Blake into a glanceable surface, like a fitness tracker complication.
- **Voice input is a nice-to-have, not urgent** — your free-text flow already handles messy input well; voice mainly helps hands-busy/driving/gym moments. Worth doing eventually, low priority now.
- **Navigation library: not yet.** Three tabs with growing per-module detail screens doesn't need Compose Navigation's full graph machinery until you have 5+ screens with back-stack complexity. Don't add it preemptively.

## 3. New module ideas

**Worth building:**
- **Body weight/measurements** — trivially deterministic (just numbers + trendline), pairs naturally with meals/workouts for the "is this working" view.
- **Sleep** — simple duration + quality log, strong correlate with everything else you're tracking, low implementation cost (manual entry; don't build wearable integration yet).
- **Habits-as-a-module** (generic recurring boolean/counter habits: water, meditation, stretching) — this generalizes streak logic across domains instead of special-casing it per module, and is a good architectural test of whether your event-log contract scales to "arbitrary user-defined trackable."

**Overreach for now:**
- **Mood/energy** — valuable in theory but hard to make deterministic or actionable without becoming a journaling app; low signal-to-effort for a side project.
- **Water** — fold into "habits" module rather than building bespoke logic for one counter.
- Anything requiring wearable/device integration (sleep tracker sync, step count) — adds auth/API surface disproportionate to payoff right now.

## 4. Technical evolution

- **Cold start:** accept it for now; a cron keep-alive ping is a reasonable cheap mitigation (every ~10 min) if latency starts actively annoying daily use, but don't pay for a tier upgrade until it's a real friction point, not a theoretical one.
- **Multi-user:** not yet. It's a real architectural change (auth, per-user data isolation, API key scoping) that only matters if you plan to share this with someone else. Don't build it speculatively.
- **Deterministic-lookup philosophy should extend to every new module** — sleep duration math, streak computation, trend aggregation should all be plain code, never model-estimated. This is the project's core integrity guarantee; don't erode it for convenience.
- **Background sync:** only needed once you add offline logging or multi-device; single-phone + always-on backend doesn't need it yet.
- **Notification infra:** local scheduled notifications (Section 2) — no FCM/push backend needed at this stage.

## 5. Prioritized roadmap (phased, one person, Claude Code as primary tool)

1. **Phase 1 — make Today alive**: status-first dashboard + trendlines (meals/workouts/spend) + one well-timed local reminder each for meals and workouts. Highest leverage, mostly UI + simple aggregation queries on existing event log.
2. **Phase 2 — reduce logging friction**: one-tap "usual" meal logging + home-screen widget. Compounds on Phase 1's data.
3. **Phase 3 — habits module + grace-streaks**: generalizes streak mechanics, adds water/meditation/etc. without bespoke modules.
4. **Phase 4 — body weight/measurements module + sleep module**: completes the "quantified self" picture, cheap to build given the event-log pattern is proven.
5. **Phase 5 — polish**: voice input, real exercise-program customization (replace generic split), cold-start mitigation if still annoying.

Multi-user, FCM, and wearable integration stay off the roadmap until a concrete need (a second real user, a specific missed reminder) forces the question.
