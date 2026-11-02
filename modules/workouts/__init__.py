from app.core import clock
from app.core.module import Module, Tool
from modules.goals import get_goals

# Rough MET (Metabolic Equivalent of Task) values per muscle group, used only to estimate
# calories burned per set — an approximation by nature, not a precise measurement. Cardio
# exercises get a higher MET; smaller muscle groups (biceps/triceps) a lower one.
MET_BY_GROUP = {
    "chest": 5.0, "back": 5.0, "legs": 6.0, "shoulders": 4.0,
    "biceps": 3.5, "triceps": 3.5, "core": 4.0, "cardio": 8.0,
}
DEFAULT_MET = 5.0
SET_DURATION_MIN = 0.75  # ~45s of actual work per strength set — a rough constant
DEFAULT_BODYWEIGHT_KG = 70.0

# Static split: (location, muscle_group) -> exercises with default sets/reps.
# Deterministic plan data, not a model guess — edit directly to match your real routine.
EXERCISES: dict[tuple[str, str], list[dict]] = {
    ("gym", "chest"): [
        {"exercise": "Bench Press", "sets": 4, "reps": "8"},
        {"exercise": "Incline Dumbbell Press", "sets": 3, "reps": "10"},
        {"exercise": "Cable Fly", "sets": 3, "reps": "12"},
        {"exercise": "Dips", "sets": 3, "reps": "10"},
    ],
    ("home", "chest"): [
        {"exercise": "Push-ups", "sets": 4, "reps": "15"},
        {"exercise": "Diamond Push-ups", "sets": 3, "reps": "12"},
        {"exercise": "Decline Push-ups", "sets": 3, "reps": "12"},
        {"exercise": "Wide Push-ups", "sets": 3, "reps": "15"},
    ],
    ("gym", "back"): [
        {"exercise": "Lat Pulldown", "sets": 4, "reps": "10"},
        {"exercise": "Barbell Row", "sets": 4, "reps": "8"},
        {"exercise": "Seated Cable Row", "sets": 3, "reps": "12"},
        {"exercise": "Face Pull", "sets": 3, "reps": "15"},
    ],
    ("home", "back"): [
        {"exercise": "Pull-ups", "sets": 4, "reps": "8"},
        {"exercise": "Superman", "sets": 3, "reps": "15"},
        {"exercise": "Inverted Row (table)", "sets": 3, "reps": "12"},
        {"exercise": "Reverse Snow Angels", "sets": 3, "reps": "15"},
    ],
    ("gym", "legs"): [
        {"exercise": "Barbell Squat", "sets": 4, "reps": "8"},
        {"exercise": "Leg Press", "sets": 3, "reps": "10"},
        {"exercise": "Romanian Deadlift", "sets": 3, "reps": "10"},
        {"exercise": "Leg Curl", "sets": 3, "reps": "12"},
    ],
    ("home", "legs"): [
        {"exercise": "Bodyweight Squats", "sets": 4, "reps": "20"},
        {"exercise": "Lunges", "sets": 3, "reps": "12 per leg"},
        {"exercise": "Glute Bridges", "sets": 3, "reps": "15"},
        {"exercise": "Wall Sit", "sets": 3, "reps": "45s"},
    ],
    ("gym", "shoulders"): [
        {"exercise": "Overhead Press", "sets": 4, "reps": "8"},
        {"exercise": "Lateral Raise", "sets": 3, "reps": "12"},
        {"exercise": "Front Raise", "sets": 3, "reps": "12"},
        {"exercise": "Rear Delt Fly", "sets": 3, "reps": "15"},
    ],
    ("home", "shoulders"): [
        {"exercise": "Pike Push-ups", "sets": 4, "reps": "10"},
        {"exercise": "Arm Circles", "sets": 3, "reps": "30s"},
        {"exercise": "Lateral Raise (water bottles)", "sets": 3, "reps": "15"},
        {"exercise": "Plank to Downward Dog", "sets": 3, "reps": "12"},
    ],
    ("gym", "biceps"): [
        {"exercise": "Barbell Curl", "sets": 4, "reps": "10"},
        {"exercise": "Hammer Curl", "sets": 3, "reps": "12"},
        {"exercise": "Concentration Curl", "sets": 3, "reps": "12"},
        {"exercise": "Cable Curl", "sets": 3, "reps": "15"},
    ],
    ("home", "biceps"): [
        {"exercise": "Chin-ups", "sets": 4, "reps": "8"},
        {"exercise": "Resistance Band Curl", "sets": 3, "reps": "15"},
        {"exercise": "Towel Curl (isometric)", "sets": 3, "reps": "20s"},
        {"exercise": "Backpack Curl", "sets": 3, "reps": "12"},
    ],
    ("gym", "triceps"): [
        {"exercise": "Close-Grip Bench Press", "sets": 4, "reps": "8"},
        {"exercise": "Tricep Pushdown", "sets": 3, "reps": "12"},
        {"exercise": "Overhead Tricep Extension", "sets": 3, "reps": "12"},
        {"exercise": "Skull Crushers", "sets": 3, "reps": "10"},
    ],
    ("home", "triceps"): [
        {"exercise": "Diamond Push-ups", "sets": 4, "reps": "12"},
        {"exercise": "Tricep Dips (chair)", "sets": 3, "reps": "12"},
        {"exercise": "Close-Grip Push-ups", "sets": 3, "reps": "15"},
        {"exercise": "Bench Dips", "sets": 3, "reps": "12"},
    ],
    ("gym", "core"): [
        {"exercise": "Cable Crunch", "sets": 4, "reps": "15"},
        {"exercise": "Hanging Leg Raise", "sets": 3, "reps": "12"},
        {"exercise": "Russian Twist", "sets": 3, "reps": "20"},
        {"exercise": "Plank", "sets": 3, "reps": "60s"},
    ],
    ("home", "core"): [
        {"exercise": "Crunches", "sets": 4, "reps": "20"},
        {"exercise": "Leg Raises", "sets": 3, "reps": "15"},
        {"exercise": "Russian Twist", "sets": 3, "reps": "20"},
        {"exercise": "Plank", "sets": 3, "reps": "60s"},
    ],
    ("gym", "cardio"): [
        {"exercise": "Treadmill Run", "sets": 1, "reps": "20min"},
        {"exercise": "Rowing Machine", "sets": 1, "reps": "10min"},
        {"exercise": "Stair Climber", "sets": 1, "reps": "10min"},
        {"exercise": "Cycling", "sets": 1, "reps": "15min"},
    ],
    ("home", "cardio"): [
        {"exercise": "Jumping Jacks", "sets": 4, "reps": "30s"},
        {"exercise": "Burpees", "sets": 3, "reps": "15"},
        {"exercise": "High Knees", "sets": 3, "reps": "30s"},
        {"exercise": "Mountain Climbers", "sets": 3, "reps": "20"},
    ],
}


def _kcal_for_set(exercise: str, db) -> float:
    """Deterministic estimate: MET * bodyweight_kg * 3.5 / 200 * duration_minutes.
    Bodyweight comes from goals (bodyweight_kg), defaulting if not set."""
    groups = muscle_groups_for({exercise})
    met = MET_BY_GROUP.get(groups[0], DEFAULT_MET) if groups else DEFAULT_MET
    bodyweight = get_goals({}, db).get("bodyweight_kg", DEFAULT_BODYWEIGHT_KG)
    return round(met * bodyweight * 3.5 / 200 * SET_DURATION_MIN, 1)


def log_set(args: dict, db) -> dict:
    try:
        ex = str(args["exercise"]).strip().lower()
        w, reps = float(args["weight_kg"]), int(args["reps"])
    except (KeyError, ValueError, TypeError):
        return {"error": "need exercise, weight_kg, reps"}
    if w < 0 or reps <= 0:
        return {"error": "invalid weight or reps"}

    client_id = args.get("client_id")
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:  # retried request that already landed — don't redo PR detection
            p = existing["payload"]
            return {"logged": {"exercise": p["exercise"], "weight_kg": p["weight_kg"],
                                "reps": p["reps"]}, "e1rm": p["e1rm"],
                    "is_pr": False, "first_time": False, "duplicate": True,
                    "event_id": existing["id"], "kcal_burned": p.get("kcal_burned", 0.0)}

    e1rm = round(w * (1 + reps / 30), 1)  # Epley
    prev = [x["payload"]["e1rm"] for x in db.query_active("workouts", "set")
            if x["payload"]["exercise"] == ex]
    is_pr = bool(prev) and e1rm > max(prev)
    kcal_burned = _kcal_for_set(ex, db)
    event_id = db.append(
        "workouts", "set",
        {"exercise": ex, "weight_kg": w, "reps": reps, "e1rm": e1rm, "kcal_burned": kcal_burned},
        client_id=client_id)
    return {"logged": {"exercise": ex, "weight_kg": w, "reps": reps},
            "e1rm": e1rm, "is_pr": is_pr, "first_time": not prev, "event_id": event_id,
            "kcal_burned": kcal_burned}


def suggest_routine(args: dict, db) -> dict:
    location = str(args.get("location", "")).strip().lower()
    if location not in ("gym", "home"):
        return {"error": "location must be 'gym' or 'home'"}
    groups = [g.strip().lower() for g in str(args.get("muscle_group", "")).split(",") if g.strip()]
    if not groups:
        return {"error": "muscle_group is required"}
    found, unknown = [], []
    for g in groups:
        exercises = EXERCISES.get((location, g))
        if exercises:
            found.append({"muscle_group": g, "exercises": exercises})
        else:
            unknown.append(g)
    return {"location": location, "groups": found, "unknown_groups": unknown}


def muscle_groups_for(exercise_names: set[str]) -> list[str]:
    """Best-effort reverse lookup from logged exercise names to muscle group(s), using
    EXERCISES as the source of truth. Exercises logged under a name that doesn't match the
    table (e.g. a custom lift typed into chat) simply don't contribute a group — this is a
    convenience for the /today dashboard, not something any PR/number depends on."""
    name_to_groups: dict[str, set[str]] = {}
    for (_location, group), exercises in EXERCISES.items():
        for ex in exercises:
            name_to_groups.setdefault(ex["exercise"].lower(), set()).add(group)
    groups: set[str] = set()
    for name in exercise_names:
        groups.update(name_to_groups.get(name.lower(), set()))
    return sorted(groups)


def prs_in(sets_sorted_by_time: list[dict], since: float) -> list[dict]:
    """Recompute which of the given (chronologically-sorted, ascending) sets were PRs at
    the time they were logged, matching log_set's own is_pr definition exactly, and return
    the ones at or after `since`."""
    best_so_far: dict[str, float] = {}
    prs = []
    for e in sets_sorted_by_time:
        ex, e1rm = e["payload"]["exercise"], e["payload"]["e1rm"]
        prev_best = best_so_far.get(ex)
        is_pr = prev_best is not None and e1rm > prev_best
        if is_pr and e["ts"] >= since:
            prs.append({"exercise": ex, "e1rm": e1rm})
        if prev_best is None or e1rm > prev_best:
            best_so_far[ex] = e1rm
    return prs


INCREMENT_KG_BY_EQUIPMENT = {"barbell": 2.5, "dumbbell": 2.0}
DEFAULT_INCREMENT_KG = 2.5


def _target_reps_for(exercise: str) -> int | None:
    """Reverse lookup: the planned numeric rep target for this exercise, from EXERCISES.
    None if the exercise isn't in the table, or its target isn't a plain integer (e.g. a
    duration like "45s" or "12 per leg") — progressive overload's "hit every rep" check
    doesn't apply the same way to those, so there's nothing safe to compare against."""
    name = exercise.lower()
    for exercises in EXERCISES.values():
        for ex in exercises:
            if ex["exercise"].lower() == name:
                digits = ex["reps"].split()[0]
                return int(digits) if digits.isdigit() else None
    return None


def _equipment_for(exercise: str) -> str:
    return "dumbbell" if "dumbbell" in exercise.lower() else "barbell"


def suggested_next(exercise: str, db) -> dict:
    """Deterministic progressive overload (ROADMAP-NEXTGEN.md P4): if every set in the most
    recent session for this exercise hit (or exceeded) its planned rep target, suggest that
    session's weight plus a fixed increment (2.5kg barbell, 2kg dumbbell by default);
    otherwise suggest repeating the same weight. No history yet -> no suggestion."""
    ex_lower = exercise.strip().lower()
    sets = sorted(
        (e for e in db.query_active("workouts", "set", limit=10**6)
         if e["payload"]["exercise"] == ex_lower),
        key=lambda e: e["ts"],
    )
    if not sets:
        return {"exercise": ex_lower, "last_weight_kg": None, "last_reps": None, "suggested_weight_kg": None}

    last_day = clock.day_start(at=sets[-1]["ts"])
    last_session = [e for e in sets if clock.day_start(at=e["ts"]) == last_day]
    last_weight = last_session[-1]["payload"]["weight_kg"]
    last_reps = last_session[-1]["payload"]["reps"]

    target_reps = _target_reps_for(ex_lower)
    all_hit_target = target_reps is not None and all(
        s["payload"]["reps"] >= target_reps for s in last_session
    )
    increment = INCREMENT_KG_BY_EQUIPMENT.get(_equipment_for(ex_lower), DEFAULT_INCREMENT_KG)
    suggested_weight = round(last_weight + increment, 1) if all_hit_target else last_weight

    return {"exercise": ex_lower, "last_weight_kg": last_weight, "last_reps": last_reps,
            "suggested_weight_kg": suggested_weight}


DEFAULT_PROGRAM_CYCLE = ["chest", "back", "legs", "shoulders", "biceps", "triceps", "core"]
VALID_MUSCLE_GROUPS = DEFAULT_PROGRAM_CYCLE + ["cardio"]


def get_program(db) -> dict:
    """Latest workouts__set_program payload, or a default one-day-per-muscle-group cycle
    (built from the existing EXERCISES table, no rest days) if none has been set yet — "the
    default is the current table" (ROADMAP-NEXTGEN.md P4)."""
    events = db.query_active("workouts", "program", limit=1)
    if events:
        return events[0]["payload"]
    return {"days": [{"type": "train", "muscle_group": g} for g in DEFAULT_PROGRAM_CYCLE]}


def set_program(args: dict, db) -> dict:
    days = args.get("days")
    if not isinstance(days, list) or not days:
        return {"error": "days must be a non-empty list"}
    for d in days:
        if not isinstance(d, dict) or d.get("type") not in ("train", "rest"):
            return {"error": "each day needs type: 'train' or 'rest'"}
        if d["type"] == "train" and d.get("muscle_group") not in VALID_MUSCLE_GROUPS:
            return {"error": f"train days need muscle_group to be one of {VALID_MUSCLE_GROUPS}"}
    cleaned = [
        {"type": "rest"} if d["type"] == "rest" else {"type": "train", "muscle_group": d["muscle_group"]}
        for d in days
    ]
    event_id = db.append("workouts", "program", {"days": cleaned})
    return {"days": cleaned, "event_id": event_id}


def today_program_day(db) -> dict:
    """Today's day = the one after the last COMPLETED day (ROADMAP-NEXTGEN.md P4) — advances
    only when a day is explicitly marked done (complete_program_day), never by calendar date,
    so skipping entirely just resumes at the same point next time rather than falling behind
    or double-advancing."""
    days = get_program(db)["days"]
    completed = len(db.query_active("workouts", "program_day_done", limit=10**6))
    index = completed % len(days)
    return {"index": index, "day": days[index], "total_days": len(days)}


def complete_program_day(args: dict, db) -> dict:
    today = today_program_day(db)
    event_id = db.append("workouts", "program_day_done", {"day_index": today["index"]})
    return {"completed_index": today["index"], "event_id": event_id}


module = Module(
    name="workouts",
    version="0.1.0",
    system_prompt=(
        "Workouts: one log_set call per set (expand '60x8 for 3 sets' into 3 calls). "
        "Weights in kg. PR status comes from the tool. suggest_routine gives a fixed "
        "exercise plan for a location and one or more comma-separated muscle groups. "
        "set_program replaces the user's whole weekly plan when they describe one "
        "(e.g. 'make Thursday a rest day' means re-sending the full day list with that "
        "change — always include every day, not just the one being edited)."
    ),
    tools=[
        Tool(
            "log_set",
            "Log a single strength set.",
            {"type": "object", "required": ["exercise", "weight_kg", "reps"], "properties": {
                "exercise": {"type": "string"},
                "weight_kg": {"type": "number"},
                "reps": {"type": "integer"}}},
            log_set,
        ),
        Tool(
            "suggest_routine",
            "Suggest a fixed exercise plan for a location and muscle group(s).",
            {"type": "object", "required": ["location", "muscle_group"], "properties": {
                "location": {"type": "string", "enum": ["gym", "home"]},
                "muscle_group": {"type": "string",
                                  "description": "comma-separated, e.g. 'chest,biceps'"}}},
            suggest_routine,
        ),
        Tool(
            "set_program",
            "Replace the user's whole weekly training program (ordered list of days).",
            {"type": "object", "required": ["days"], "properties": {
                "days": {"type": "array", "items": {"type": "object", "properties": {
                    "type": {"type": "string", "enum": ["train", "rest"]},
                    "muscle_group": {"type": "string", "enum": VALID_MUSCLE_GROUPS}}}}}},
            set_program,
        ),
        Tool("get_program", "Get the user's current weekly training program.",
             {"type": "object", "properties": {}}, lambda args, db: get_program(db)),
    ],
)
