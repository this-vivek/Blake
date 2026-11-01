"""Aggregates every module's data into one payload for the Android app's Today dashboard.

Lives outside app/core/ (not a contract file) and outside modules/ (it reads across modules,
rather than owning its own event kind) — a thin read-only aggregation layer.
"""
from app.core import clock
from modules.workouts import muscle_groups_for, prs_in

MAX_NUDGES = 2  # ROADMAP-NEXTGEN.md P3: "at most 2 nudges"


def build_today(db, registry, now: float | None = None) -> dict:
    since = clock.day_start(at=now)

    meals_today = registry.call("meals__daily_summary", {}, db)["today"]
    meal_events = db.query_active("meals", "meal", since=since)
    meal_slots_logged = sorted(
        {e["payload"].get("meal", "unspecified") for e in meal_events} - {"unspecified"}
    )

    sets_today = db.query_active("workouts", "set", since=since)
    all_sets_sorted = sorted(db.query_active("workouts", "set", limit=10**6), key=lambda e: e["ts"])
    prs_today = prs_in(all_sets_sorted, since)
    muscle_groups_today = muscle_groups_for({e["payload"]["exercise"] for e in sets_today})

    spend_today = registry.call("expenses__daily_summary", {}, db)["today"]
    goals = registry.call("goals__get_goals", {}, db)
    open_tasks = registry.call("tasks__list_tasks", {}, db)["open_tasks"]

    nudges = []
    protein_goal = goals.get("protein_g")
    if protein_goal is not None and meals_today["protein"] < protein_goal:
        short = protein_goal - meals_today["protein"]
        nudges.append(f"{short:.0f}g protein short today")
    spend_goal = goals.get("daily_spend")
    if spend_goal is not None and spend_today["total"] > spend_goal:
        over = spend_today["total"] - spend_goal
        nudges.append(f"over your daily spend budget by {over:.0f}")
    if not meal_slots_logged:
        nudges.append("no meals logged yet today")

    return {
        "nutrition": {
            "kcal": {"actual": meals_today["kcal"], "goal": goals.get("kcal")},
            "protein_g": {"actual": meals_today["protein"], "goal": goals.get("protein_g")},
            "meal_slots_logged": meal_slots_logged,
        },
        "training": {
            "sets_today": [
                {"exercise": e["payload"]["exercise"], "weight_kg": e["payload"]["weight_kg"],
                 "reps": e["payload"]["reps"], "e1rm": e["payload"]["e1rm"]}
                for e in sets_today
            ],
            "muscle_groups_today": muscle_groups_today,
            "prs_today": prs_today,
        },
        "spend": {"actual": spend_today["total"], "goal": goals.get("daily_spend")},
        "open_tasks": open_tasks,
        "nudges": nudges[:MAX_NUDGES],
    }
