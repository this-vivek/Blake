from app.core.module import Module, Tool

# field -> (cast, validator). Each set_goal call may touch any subset of these.
FIELDS = {
    "kcal": (float, lambda v: v > 0),
    "protein_g": (float, lambda v: v >= 0),
    "daily_spend": (float, lambda v: v >= 0),
    "workout_days_per_week": (int, lambda v: 0 <= v <= 7),
    "bodyweight_kg": (float, lambda v: 20 <= v <= 300),  # used by workouts' kcal-burned estimate
}


def set_goal(args: dict, db) -> dict:
    update = {}
    for field, (cast, valid) in FIELDS.items():
        if args.get(field) is None:
            continue
        try:
            value = cast(args[field])
        except (TypeError, ValueError):
            return {"error": f"{field} must be a number"}
        if not valid(value):
            return {"error": f"{field} is out of range"}
        update[field] = value
    if not update:
        return {"error": f"provide at least one of: {', '.join(FIELDS)}"}
    db.append("goals", "goal", update)
    return {"updated": update, "goals": get_goals({}, db)}


def get_goals(args: dict, db) -> dict:
    """Latest value per field wins — a set_goal touching only one field doesn't reset others."""
    current: dict = {}
    for e in db.query_active("goals", "goal", limit=10**6):  # newest first
        for k, v in e["payload"].items():
            current.setdefault(k, v)
    return current


module = Module(
    name="goals",
    version="0.1.0",
    system_prompt=(
        "Goals: call set_goal when the user states a target, e.g. 'set protein target to "
        "140g' -> protein_g=140. Only the field(s) mentioned change; others keep their last "
        "set value. Call get_goals to check current targets."
    ),
    tools=[
        Tool(
            "set_goal",
            "Set one or more daily/weekly goals. Unmentioned fields are left unchanged.",
            {"type": "object", "properties": {
                "kcal": {"type": "number"},
                "protein_g": {"type": "number"},
                "daily_spend": {"type": "number"},
                "workout_days_per_week": {"type": "integer"},
                "bodyweight_kg": {"type": "number"},
            }},
            set_goal,
        ),
        Tool("get_goals", "Get current goals.", {"type": "object", "properties": {}}, get_goals),
    ],
)
