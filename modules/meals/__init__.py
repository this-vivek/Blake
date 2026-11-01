import re

from app.core.module import Module, Tool

# Per 100 g: kcal, protein, carbs, fat. Static dataset (approx. USDA/IFCT values) so lookups
# stay deterministic and offline — no network calls from a tool handler.
FOODS = {
    "roti": (297, 9.8, 51.0, 7.5),
    "dal": (116, 7.0, 17.0, 2.5),
    "rice": (130, 2.7, 28.0, 0.3),
    "egg": (143, 12.6, 0.7, 9.5),
    "paneer": (265, 18.3, 1.2, 20.8),
    "chicken breast": (165, 31.0, 0.0, 3.6),
    "corn": (96, 3.4, 21.0, 1.5),
    "banana": (89, 1.1, 22.8, 0.3),
    "apple": (52, 0.3, 13.8, 0.2),
    "orange": (47, 0.9, 11.8, 0.1),
    "mango": (60, 0.8, 15.0, 0.4),
    "milk": (42, 3.4, 5.0, 1.0),
    "curd": (98, 3.3, 11.0, 4.3),
    "yogurt": (59, 10.0, 3.6, 0.4),
    "oats": (389, 16.9, 66.3, 6.9),
    "bread": (265, 9.0, 49.0, 3.2),
    "butter": (717, 0.9, 0.1, 81.1),
    "ghee": (900, 0.0, 0.0, 100.0),
    "potato": (77, 2.0, 17.0, 0.1),
    "sweet potato": (86, 1.6, 20.1, 0.1),
    "tomato": (18, 0.9, 3.9, 0.2),
    "onion": (40, 1.1, 9.3, 0.1),
    "spinach": (23, 2.9, 3.6, 0.4),
    "fish": (206, 22.0, 0.0, 12.0),
    "salmon": (208, 20.4, 0.0, 13.4),
    "tofu": (76, 8.0, 1.9, 4.8),
    "chickpea": (164, 8.9, 27.4, 2.6),
    "lentils": (116, 9.0, 20.1, 0.4),
    "almonds": (579, 21.2, 21.6, 49.9),
    "peanut": (567, 25.8, 16.1, 49.2),
    "peanut butter": (588, 25.1, 20.0, 50.4),
    "cheese": (402, 25.0, 1.3, 33.1),
    "sugar": (387, 0.0, 100.0, 0.0),
    "cooking oil": (884, 0.0, 0.0, 100.0),
    "avocado": (160, 2.0, 8.5, 14.7),
    "cucumber": (15, 0.7, 3.6, 0.1),
    "carrot": (41, 0.9, 9.6, 0.2),
    "beans": (127, 8.7, 23.0, 0.5),
    "quinoa": (120, 4.4, 21.3, 1.9),
    "pasta": (131, 5.0, 25.0, 1.1),
    "idli": (156, 4.0, 32.0, 0.5),
    "dosa": (168, 3.9, 28.0, 3.7),
    "chapati": (297, 9.8, 51.0, 7.5),
    "poha": (130, 2.7, 24.0, 3.0),
    "whey protein": (400, 80.0, 10.0, 3.3),
    "protein powder": (400, 80.0, 10.0, 3.3),
}

# Common qualifiers Claude adds that shouldn't block a match (boiled, cooked, raw, etc.).
_NOISE_WORDS = {
    "boiled", "cooked", "raw", "fried", "grilled", "steamed", "roasted", "baked",
    "whole", "fresh", "hard-boiled", "scrambled", "without", "salt", "drained",
    "sweet", "yellow", "kernels", "slices", "chopped", "sliced", "diced",
}


def _match(name: str) -> tuple[str, tuple] | None:
    """Exact match first, then match a known food as a whole word within the input.

    Parenthetical asides (e.g. "poha (rice flakes with onion, peanuts)") are stripped before
    matching — they describe ingredients of the dish, not the dish itself, and matching on
    them causes false positives (e.g. matching "onion" instead of the actual dish "poha").
    """
    if name in FOODS:
        return name, FOODS[name]
    primary = re.sub(r"\([^)]*\)", " ", name)
    words = {w for w in primary.replace(",", " ").split() if w not in _NOISE_WORDS}
    candidates = [k for k in FOODS if set(k.split()) <= words]
    if candidates:
        best = max(candidates, key=len)
        return best, FOODS[best]
    return None


def log_meal(args: dict, db) -> dict:
    items_in = args.get("items")
    if not isinstance(items_in, list) or not items_in:
        return {"error": "items must be a non-empty list"}
    items, unknown = [], []
    total = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for it in items_in:
        raw_name = str(it.get("name", "")).strip().lower()
        grams = float(it.get("grams", 0))
        matched = _match(raw_name) if raw_name else None
        if not matched or grams <= 0:
            unknown.append(raw_name or "?")
            continue
        name, per100 = matched
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
