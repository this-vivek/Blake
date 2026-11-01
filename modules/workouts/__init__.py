from app.core.module import Module, Tool


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


module = Module(
    name="workouts",
    version="0.1.0",
    system_prompt=(
        "Workouts: one log_set call per set (expand '60x8 for 3 sets' into 3 calls). "
        "Weights in kg. PR status comes from the tool."
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
        )
    ],
)
