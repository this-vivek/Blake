from app.core.module import Module, Tool

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


def log_set(args: dict, db) -> dict:
    try:
        ex = str(args["exercise"]).strip().lower()
        w, reps = float(args["weight_kg"]), int(args["reps"])
    except (KeyError, ValueError, TypeError):
        return {"error": "need exercise, weight_kg, reps"}
    if w < 0 or reps <= 0:
        return {"error": "invalid weight or reps"}
    e1rm = round(w * (1 + reps / 30), 1)  # Epley
    prev = [x["payload"]["e1rm"] for x in db.query("workouts", "set")
            if x["payload"]["exercise"] == ex]
    is_pr = bool(prev) and e1rm > max(prev)
    db.append("workouts", "set", {"exercise": ex, "weight_kg": w, "reps": reps, "e1rm": e1rm})
    return {"logged": {"exercise": ex, "weight_kg": w, "reps": reps},
            "e1rm": e1rm, "is_pr": is_pr, "first_time": not prev}


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


module = Module(
    name="workouts",
    version="0.1.0",
    system_prompt=(
        "Workouts: one log_set call per set (expand '60x8 for 3 sets' into 3 calls). "
        "Weights in kg. PR status comes from the tool. suggest_routine gives a fixed "
        "exercise plan for a location and one or more comma-separated muscle groups."
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
    ],
)
