"""Aggregates every module's data into one payload for the Android app's Today dashboard.

Lives outside app/core/ (not a contract file) and outside modules/ (it reads across modules,
rather than owning its own event kind) — a thin read-only aggregation layer.
"""
from app.core import clock
from modules.meals import protein_gap_foods, usual_combo, usual_log_hour
from modules.workouts import muscle_groups_for, prs_in

MAX_NUDGES = 2  # ROADMAP-NEXTGEN.md P3: "at most 2 nudges"
MEAL_SLOTS = ["breakfast", "lunch", "dinner", "snack"]
EVENING_HOUR = 18.0  # "Protein is short in the evening" (ROADMAP-NEXTGEN.md P3)


def build_today(db, registry, now: float | None = None) -> dict:
    at = now if now is not None else clock.now()
    since = clock.day_start(at=at)
    current_hour = clock.local_hour(at)

    meals_today = registry.call("meals__daily_summary", {}, db)["today"]
    meal_events = db.query_active("meals", "meal", since=since)
    meal_slots_logged = sorted(
        {e["payload"].get("meal", "unspecified") for e in meal_events} - {"unspecified"}
    )

    sets_today = db.query_active("workouts", "set", since=since)
    warmups_today = db.query_active("workouts", "warmup", since=since)
    all_sets_sorted = sorted(db.query_active("workouts", "set", limit=10**6), key=lambda e: e["ts"])
    prs_today = prs_in(all_sets_sorted, since)
    muscle_groups_today = muscle_groups_for({e["payload"]["exercise"] for e in sets_today})

    spend_today = registry.call("expenses__daily_summary", {}, db)["today"]
    habits_today = registry.call("habits__habit_status", {}, db)["habits"]
    weight_today = registry.call("body__daily_summary", {}, db)["today"]
    sleep_today = registry.call("sleep__daily_summary", {}, db)["today"]
    goals = registry.call("goals__get_goals", {}, db)
    open_tasks = registry.call("tasks__list_tasks", {}, db)["open_tasks"]

    nudges = []
    protein_goal = goals.get("protein_g")
    if (protein_goal is not None and meals_today["protein"] < protein_goal
            and current_hour >= EVENING_HOUR):
        short = protein_goal - meals_today["protein"]
        foods = protein_gap_foods(db)
        suffix = f" — try {', '.join(foods)}" if foods else ""
        nudges.append(f"{short:.0f}g protein short today{suffix}")
    spend_goal = goals.get("daily_spend")
    if spend_goal is not None and spend_today["total"] > spend_goal:
        over = spend_today["total"] - spend_goal
        nudges.append(f"over your daily spend budget by {over:.0f}")
    for slot in MEAL_SLOTS:
        if slot in meal_slots_logged:
            continue
        usual_hour = usual_log_hour(slot, db)
        if usual_hour is not None and current_hour >= usual_hour:
            nudges.append(f"{slot} usually logged by now — still unlogged")

    return {
        "nutrition": {
            "kcal": {"actual": meals_today["kcal"], "goal": goals.get("kcal")},
            "protein_g": {"actual": meals_today["protein"], "goal": goals.get("protein_g")},
            "meal_slots_logged": meal_slots_logged,
            "usual": {
                # usual_hour (median local hour this slot is logged) drives the app's reminders
                slot: {**usual_combo(slot, db), "usual_hour": usual_log_hour(slot, db)}
                for slot in MEAL_SLOTS
            },
        },
        "training": {
            "sets_today": [
                {"exercise": e["payload"]["exercise"], "weight_kg": e["payload"]["weight_kg"],
                 "reps": e["payload"]["reps"], "e1rm": e["payload"]["e1rm"]}
                for e in sets_today
            ],
            "muscle_groups_today": muscle_groups_today,
            "prs_today": prs_today,
            # Durable regardless of app lifecycle — every set is logged (and its kcal_burned
            # computed) the moment it happens, not batched at "finish session".
            "kcal_burned_today": round(
                sum(e["payload"].get("kcal_burned", 0.0) for e in sets_today + warmups_today), 1
            ),
        },
        "spend": {"actual": spend_today["total"], "goal": goals.get("daily_spend")},
        "habits": habits_today,
        "weight": weight_today,
        "sleep": sleep_today,
        "open_tasks": open_tasks,
        "nudges": nudges[:MAX_NUDGES],
    }
