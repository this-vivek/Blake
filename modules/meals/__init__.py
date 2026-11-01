from app.core.module import Module, Tool

# Per 100 g: kcal, protein, carbs, fat. TODO: replace with IFCT / USDA lookup (sqlite or API).
FOODS = {
    "roti": (297, 9.8, 51.0, 7.5),
    "dal": (116, 7.0, 17.0, 2.5),
    "rice": (130, 2.7, 28.0, 0.3),
    "egg": (143, 12.6, 0.7, 9.5),
    "paneer": (265, 18.3, 1.2, 20.8),
    "chicken breast": (165, 31.0, 0.0, 3.6),
}


def log_meal(args: dict, db) -> dict:
    items_in = args.get("items")
    if not isinstance(items_in, list) or not items_in:
        return {"error": "items must be a non-empty list"}
    items, unknown = [], []
    total = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for it in items_in:
        name = str(it.get("name", "")).strip().lower()
        grams = float(it.get("grams", 0))
        per100 = FOODS.get(name)
        if not per100 or grams <= 0:
            unknown.append(name or "?")
            continue
        m = dict(zip(("kcal", "protein", "carbs", "fat"), (v * grams / 100 for v in per100)))
        items.append({"name": name, "grams": grams, **{k: round(v, 1) for k, v in m.items()}})
        for k in total:
            total[k] += m[k]
    total = {k: round(v, 1) for k, v in total.items()}
    if items:
        db.append("meals", "meal", {"meal": args.get("meal", "unspecified"),
                                    "items": items, "total": total})
    return {"logged": items, "total": total, "unknown_items": unknown}


def daily_summary(args: dict, db) -> dict:
    import time
    since = time.time() - 24 * 3600
    tot = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for e in db.query("meals", "meal", since=since):
        for k in tot:
            tot[k] += e["payload"]["total"][k]
    return {"last_24h": {k: round(v, 1) for k, v in tot.items()}}


module = Module(
    name="meals",
    version="0.1.0",
    system_prompt=(
        "Meals: identify each food and estimate grams (state assumptions briefly). "
        "Call meals__log_meal with items; macros are computed by the tool. "
        "Items the tool reports as unknown must be mentioned to the user."
    ),
    tools=[
        Tool(
            "log_meal",
            "Log a meal from identified foods with estimated grams.",
            {"type": "object", "required": ["items"], "properties": {
                "meal": {"type": "string", "enum": ["breakfast", "lunch", "dinner", "snack"]},
                "items": {"type": "array", "items": {"type": "object",
                          "required": ["name", "grams"], "properties": {
                              "name": {"type": "string"}, "grams": {"type": "number"}}}}}},
            log_meal,
        ),
        Tool("daily_summary", "Macro totals for the last 24 hours.",
             {"type": "object", "properties": {}}, daily_summary),
    ],
)
