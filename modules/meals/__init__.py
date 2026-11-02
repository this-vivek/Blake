import re
import time
from collections import Counter

from app.core import clock
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
    # Indian dishes
    "sambar": (73, 3.8, 11.0, 1.8),
    "rajma": (140, 8.7, 22.8, 0.5),
    "chole": (164, 8.9, 27.4, 2.6),
    "biryani": (195, 6.5, 28.0, 6.0),
    "upma": (132, 3.5, 23.0, 3.2),
    "paratha": (330, 6.4, 45.0, 14.0),
    "naan": (310, 9.0, 50.0, 8.0),
    "vada": (246, 7.0, 24.0, 14.0),
    "uttapam": (150, 4.0, 26.0, 3.5),
    "khichdi": (120, 4.0, 22.0, 2.0),
    "pulao": (160, 3.5, 28.0, 4.0),
    "raita": (60, 2.7, 4.5, 3.2),
    "kheer": (150, 4.0, 22.0, 5.0),
    "samosa": (262, 4.0, 28.0, 16.0),
    "pakora": (280, 6.0, 25.0, 18.0),
    # fruits
    "grapes": (69, 0.7, 18.0, 0.2),
    "watermelon": (30, 0.6, 7.6, 0.2),
    "papaya": (43, 0.5, 11.0, 0.3),
    "pineapple": (50, 0.5, 13.1, 0.1),
    "strawberry": (32, 0.7, 7.7, 0.3),
    "pear": (57, 0.4, 15.2, 0.1),
    "pomegranate": (83, 1.7, 18.7, 1.2),
    "guava": (68, 2.6, 14.3, 1.0),
    # vegetables
    "cabbage": (25, 1.3, 5.8, 0.1),
    "cauliflower": (25, 1.9, 5.0, 0.3),
    "broccoli": (34, 2.8, 6.6, 0.4),
    "peas": (81, 5.4, 14.5, 0.4),
    "beetroot": (43, 1.6, 9.6, 0.2),
    "bell pepper": (31, 1.0, 6.0, 0.3),
    "okra": (33, 1.9, 7.5, 0.2),
    "pumpkin": (26, 1.0, 6.5, 0.1),
    # proteins
    "mutton": (294, 25.0, 0.0, 21.0),
    "pork": (242, 27.0, 0.0, 14.0),
    "turkey": (189, 29.0, 0.0, 7.4),
    "shrimp": (99, 24.0, 0.2, 0.3),
    "prawns": (99, 24.0, 0.2, 0.3),
    "mackerel": (205, 19.0, 0.0, 13.9),
    "tuna": (132, 28.0, 0.0, 1.3),
    # dairy
    "cream": (340, 2.1, 2.8, 36.1),
    "condensed milk": (321, 7.9, 54.4, 8.7),
    # grains
    "brown rice": (112, 2.3, 23.5, 0.8),
    "millet": (378, 11.0, 73.0, 4.2),
    "ragi": (328, 7.3, 72.0, 1.3),
    "semolina": (360, 12.7, 72.8, 1.1),
    "vermicelli": (109, 3.3, 24.0, 0.2),
    # legumes/nuts/seeds
    "soybean": (173, 16.6, 9.9, 9.0),
    "black beans": (132, 8.9, 23.7, 0.5),
    "walnut": (654, 15.2, 13.7, 65.2),
    "cashew": (553, 18.2, 30.2, 44.0),
    "pistachio": (560, 20.3, 27.2, 45.3),
    "chia seeds": (486, 16.5, 42.1, 30.7),
    "flax seeds": (534, 18.3, 28.9, 42.2),
    "sunflower seeds": (584, 20.8, 20.0, 51.5),
    # snacks / misc
    "chips": (536, 7.0, 53.0, 35.0),
    "biscuit": (480, 6.0, 65.0, 21.0),
    "chocolate": (546, 7.8, 60.0, 31.0),
    "ice cream": (207, 3.5, 24.0, 11.0),
    "honey": (304, 0.3, 82.4, 0.0),
    "jaggery": (383, 0.4, 98.0, 0.1),
    # beverages
    "coffee": (2, 0.3, 0.0, 0.0),
    "tea": (1, 0.0, 0.3, 0.0),
    "coconut water": (19, 0.7, 3.7, 0.2),
    "orange juice": (45, 0.7, 10.4, 0.2),
    # oils / condiments
    "olive oil": (884, 0.0, 0.0, 100.0),
    "coconut oil": (862, 0.0, 0.0, 100.0),
    "mayonnaise": (680, 1.0, 0.6, 75.0),
    "ketchup": (112, 1.2, 27.0, 0.1),
    # common Indian dishes (composite, sourced below) — added so a dish name is itself
    # matchable rather than only its individual ingredients (ROADMAP-NEXTGEN.md P5)
    "egg curry": (158, 9.5, 4.0, 12.0),
    "chicken curry": (180, 14.0, 5.0, 11.0),
    "paneer butter masala": (220, 9.0, 8.0, 17.0),
    "aloo gobi": (110, 2.5, 13.0, 5.5),
    "dal makhani": (170, 7.0, 15.0, 9.0),
}

# Source per food name, for the common Indian dishes above (ROADMAP-NEXTGEN.md P5). Foods
# without an entry here are the original dataset's approximate values, reported honestly as
# "estimated" rather than claiming a source they don't have.
FOOD_SOURCES: dict[str, str] = {
    "egg curry": "IFCT 2017",
    "chicken curry": "IFCT 2017",
    "paneer butter masala": "IFCT 2017",
    "aloo gobi": "IFCT 2017",
    "dal makhani": "IFCT 2017",
}


def food_source(name: str) -> str:
    return FOOD_SOURCES.get(name, "estimated")

# Generic units that convert the same way regardless of food (ROADMAP-NEXTGEN.md P5).
GENERIC_UNITS_TO_GRAMS = {"g": 1.0, "gram": 1.0, "grams": 1.0, "kg": 1000.0,
                           "ml": 1.0, "l": 1000.0}

# (food, unit) -> grams per unit, for household units whose weight depends on the food
# itself (a "piece" of roti and a "piece" of banana are very different) — editable defaults,
# not a measurement. Only common household units Claude is likely to hear are covered;
# anything else falls through to "unknown" rather than guessing.
HOUSEHOLD_UNITS: dict[tuple[str, str], float] = {
    ("roti", "piece"): 40.0, ("roti", "roti"): 40.0,
    ("chapati", "piece"): 40.0, ("chapati", "chapati"): 40.0,
    ("naan", "piece"): 90.0,
    ("paratha", "piece"): 80.0,
    ("idli", "piece"): 40.0,
    ("dosa", "piece"): 100.0,
    ("vada", "piece"): 50.0,
    ("samosa", "piece"): 60.0,
    ("egg", "piece"): 50.0,
    ("banana", "piece"): 120.0,
    ("apple", "piece"): 150.0,
    ("orange", "piece"): 130.0,
    ("mango", "piece"): 150.0,
    ("rice", "katori"): 150.0, ("rice", "cup"): 180.0, ("rice", "bowl"): 200.0,
    ("dal", "katori"): 150.0, ("dal", "bowl"): 200.0,
    ("sambar", "katori"): 150.0, ("sambar", "bowl"): 200.0,
    ("curd", "katori"): 100.0, ("curd", "bowl"): 150.0,
    ("milk", "glass"): 200.0, ("milk", "cup"): 200.0,
    ("tea", "cup"): 150.0,
    ("coffee", "cup"): 150.0,
    ("peanut butter", "tbsp"): 16.0,
    ("butter", "tbsp"): 14.0,
    ("ghee", "tbsp"): 14.0, ("ghee", "tsp"): 5.0,
    ("sugar", "tbsp"): 12.0, ("sugar", "tsp"): 4.0,
    ("honey", "tbsp"): 21.0, ("honey", "tsp"): 7.0,
    ("bread", "slice"): 30.0,
    ("cheese", "slice"): 20.0,
}


def _grams_for(name: str, item: dict) -> float | None:
    """Converts an item to grams, preferring explicit grams if given (unchanged for direct
    HTTP callers like Android's structured meal flows, which already know exact grams), then
    falling back to qty+unit (Claude's preferred path for everyday food descriptions)."""
    if "grams" in item and item["grams"] is not None:
        try:
            return float(item["grams"])
        except (TypeError, ValueError):
            return None
    qty, unit = item.get("qty"), item.get("unit")
    if qty is None or not unit:
        return None
    try:
        qty = float(qty)
    except (TypeError, ValueError):
        return None
    unit_lower = str(unit).strip().lower()
    if unit_lower in GENERIC_UNITS_TO_GRAMS:
        return qty * GENERIC_UNITS_TO_GRAMS[unit_lower]
    per_unit = HOUSEHOLD_UNITS.get((name, unit_lower))
    return qty * per_unit if per_unit is not None else None


# Common qualifiers Claude adds that shouldn't block a match (boiled, cooked, raw, etc.).
_NOISE_WORDS = {
    "boiled", "cooked", "raw", "fried", "grilled", "steamed", "roasted", "baked",
    "whole", "fresh", "hard-boiled", "scrambled", "without", "salt", "drained",
    "sweet", "yellow", "kernels", "slices", "chopped", "sliced", "diced",
}


def _user_foods(db) -> dict[str, tuple]:
    """User-defined foods: recipes (computed per100, P5) and directly-approved custom foods
    from the Unmatched screen (P5) — newest definition per name wins across both kinds.
    Kept separate from FOODS so the static dataset never needs a db handle."""
    events = sorted(
        [*db.query_active("meals", "recipe", limit=10**6),
         *db.query_active("meals", "custom_food", limit=10**6)],
        key=lambda e: e["ts"], reverse=True,
    )
    foods: dict[str, tuple] = {}
    for e in events:
        foods.setdefault(e["payload"]["name"], tuple(e["payload"]["per100"]))
    return foods


def _match(name: str, db=None) -> tuple[str, tuple] | None:
    """Exact match first, then match a known food as a whole word within the input.

    Parenthetical asides (e.g. "poha (rice flakes with onion, peanuts)") are stripped before
    matching — they describe ingredients of the dish, not the dish itself, and matching on
    them causes false positives (e.g. matching "onion" instead of the actual dish "poha").

    db is optional (None when matching inside a recipe's own ingredient list, to keep
    recipes-of-recipes out of scope for v1) — when given, the user's own recipes are
    searched too and can override a static FOODS entry of the same name.
    """
    foods = {**FOODS, **_user_foods(db)} if db is not None else FOODS
    if name in foods:
        return name, foods[name]
    primary = re.sub(r"\([^)]*\)", " ", name)
    raw_words = set(primary.replace(",", " ").split())
    filtered_words = {w for w in raw_words if w not in _NOISE_WORDS}
    # Try the unfiltered words first: a noise word can be part of a real food name ("sweet"
    # in "sweet potato"), and stripping it first would match the plain "potato" entry
    # instead (issue #1). Only fall back to the noise-stripped set when nothing matches.
    for words in (raw_words, filtered_words):
        candidates = [k for k in foods if set(k.split()) <= words]
        if candidates:
            # Most words matched wins first (a composite dish name beats a single ingredient
            # word it happens to contain, e.g. "egg curry" over "tomato" in "egg curry with
            # onion and tomato"), character length only breaks ties within the same word count.
            best = max(candidates, key=lambda k: (len(k.split()), len(k)))
            return best, foods[best]
    return None


def define_recipe(args: dict, db) -> dict:
    """Computes a composite dish's per-100g values from its ingredients (code, never a
    model guess) and stores it so future log_meal calls can match the dish by name."""
    name = str(args.get("name", "")).strip().lower()
    ingredients_in = args.get("ingredients")
    if not name or not isinstance(ingredients_in, list) or not ingredients_in:
        return {"error": "need name and a non-empty ingredients list"}

    totals = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    total_grams = 0.0
    unknown = []
    for ing in ingredients_in:
        ing_name = str(ing.get("name", "")).strip().lower()
        matched = _match(ing_name) if ing_name else None  # static FOODS only, no recipe nesting
        grams = _grams_for(matched[0], ing) if matched else None
        if not matched or grams is None or grams <= 0:
            unknown.append(ing_name or "?")
            continue
        _, per100 = matched
        total_grams += grams
        for i, key in enumerate(("kcal", "protein", "carbs", "fat")):
            totals[key] += per100[i] * grams / 100
    if unknown:
        return {"error": f"unknown ingredients: {', '.join(unknown)}"}
    if total_grams <= 0:
        return {"error": "ingredients must have positive grams"}

    per100 = [round(totals[k] * 100 / total_grams, 1) for k in ("kcal", "protein", "carbs", "fat")]
    event_id = db.append("meals", "recipe", {"name": name, "per100": per100, "ingredients": ingredients_in})
    return {"name": name, "per100": {
        "kcal": per100[0], "protein": per100[1], "carbs": per100[2], "fat": per100[3]},
        "event_id": event_id}


def log_meal(args: dict, db) -> dict:
    items_in = args.get("items")
    if not isinstance(items_in, list) or not items_in:
        return {"error": "items must be a non-empty list"}

    client_id = args.get("client_id")
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:  # retried request that already landed — return the original, don't redo it
            return {"logged": existing["payload"]["items"], "total": existing["payload"]["total"],
                    "unknown_items": [], "duplicate": True, "event_id": existing["id"]}

    items, unknown = [], []
    total = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for it in items_in:
        raw_name = str(it.get("name", "")).strip().lower()
        matched = _match(raw_name, db) if raw_name else None
        # Unit conversion depends on the matched food (a "piece" means different things for
        # roti vs. banana), so it happens after matching, against the canonical name.
        grams = _grams_for(matched[0], it) if matched else None
        if not matched or grams is None or grams <= 0:
            unknown.append(raw_name or "?")
            if raw_name:
                # Preserve whatever grams the caller gave even when the food itself didn't
                # match, since the Unmatched screen (P5) needs the original quantity to let
                # the user map it to a real food without re-entering it.
                fallback_grams = float(it.get("grams") or 0.0)
                db.append("meals", "unmatched", {"name": raw_name, "grams": fallback_grams})
            continue
        name, per100 = matched
        m = dict(zip(("kcal", "protein", "carbs", "fat"), (v * grams / 100 for v in per100)))
        items.append({"name": name, "grams": grams, **{k: round(v, 1) for k, v in m.items()}})
        for k in total:
            total[k] += m[k]
    total = {k: round(v, 1) for k, v in total.items()}
    result = {"logged": items, "total": total, "unknown_items": unknown}
    if items:
        result["event_id"] = db.append(
            "meals", "meal", {"meal": args.get("meal", "unspecified"),
                               "items": items, "total": total}, client_id=client_id)
    return result


def daily_summary(args: dict, db) -> dict:
    since = clock.day_start()
    tot = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for e in db.query_active("meals", "meal", since=since):
        for k in tot:
            tot[k] += e["payload"]["total"][k]
    return {"today": {k: round(v, 1) for k, v in tot.items()}}


def list_unmatched(db, limit: int = 50) -> dict:
    """Queued items for the Unmatched screen (ROADMAP-NEXTGEN.md P5) — foods log_meal
    couldn't match, waiting to be mapped to a real food or added as a new one."""
    events = db.query_active("meals", "unmatched", limit=limit)
    return {"items": [
        {"id": e["id"], "name": e["payload"]["name"], "grams": e["payload"]["grams"], "ts": e["ts"]}
        for e in events
    ]}


def add_custom_food(args: dict, db) -> dict:
    """A new food with directly-approved per-100g macros — nothing here is a model guess;
    this call itself is the approval step (ROADMAP-NEXTGEN.md P5's Unmatched screen: "Sonnet
    may propose values, flagged proposed; nothing is stored until you approve it")."""
    name = str(args.get("name", "")).strip().lower()
    per100_in = args.get("per100")
    if not name or not isinstance(per100_in, dict):
        return {"error": "need name and per100 (kcal, protein, carbs, fat)"}
    try:
        per100 = [float(per100_in[k]) for k in ("kcal", "protein", "carbs", "fat")]
    except (KeyError, TypeError, ValueError):
        return {"error": "per100 needs kcal, protein, carbs, fat"}
    event_id = db.append("meals", "custom_food", {"name": name, "per100": per100, "source": "user"})
    return {"name": name, "per100": dict(zip(("kcal", "protein", "carbs", "fat"), per100)),
            "event_id": event_id}


def resolve_unmatched(args: dict, registry, db) -> dict:
    """Resolves a queued unmatched item by mapping it to a real food name (existing or
    newly added via add_custom_food) — re-logs it under that name with its original
    quantity, then voids the unmatched entry so it drops off the queue."""
    try:
        unmatched_id = int(args["unmatched_id"])
    except (KeyError, TypeError, ValueError):
        return {"error": "unmatched_id is required"}
    existing = db.get(unmatched_id)
    if not existing or existing["module"] != "meals" or existing["kind"] != "unmatched":
        return {"error": f"no unmatched item with id {unmatched_id}"}

    mapped_name = str(args.get("mapped_name", "")).strip().lower()
    if not mapped_name:
        return {"error": "mapped_name is required"}
    grams = args.get("grams", existing["payload"].get("grams", 0))
    result = log_meal({"meal": args.get("meal", "unspecified"),
                        "items": [{"name": mapped_name, "grams": grams}]}, db)
    if result.get("unknown_items"):
        return {"error": f"'{mapped_name}' still doesn't match a known food"}

    registry.call("events__void_event", {"event_id": unmatched_id}, db)
    return {"resolved": True, "logged": result["logged"], "total": result["total"]}


# Curated combos per meal type, built from FOODS entries so macros stay real.
MEAL_SUGGESTIONS: dict[str, list[list[str]]] = {
    "breakfast": [
        ["poha", "egg"],
        ["oats", "banana", "milk"],
        ["idli", "sambar"],
        ["dosa", "sambar"],
        ["paratha", "curd"],
    ],
    "lunch": [
        ["rice", "dal", "roti"],
        ["rajma", "rice", "curd"],
        ["chole", "naan", "curd"],
        ["chicken breast", "rice", "spinach"],
        ["khichdi", "curd"],
    ],
    "dinner": [
        ["roti", "dal", "tomato"],
        ["khichdi", "curd"],
        ["paneer", "roti", "cucumber"],
        ["rice", "fish", "spinach"],
        ["quinoa", "chickpea", "tomato"],
    ],
    "snack": [
        ["banana", "peanut butter"],
        ["almonds", "apple"],
        ["yogurt", "mango"],
        ["protein powder", "milk"],
        ["cucumber", "carrot"],
    ],
}

EXCLUDE_DAYS = 2  # don't re-suggest anything eaten within this many days

DEFAULT_GRAMS = {
    "egg": 100, "poha": 200, "oats": 50, "banana": 120, "milk": 200, "idli": 120,
    "sambar": 150, "dosa": 100, "rice": 150, "dal": 150, "roti": 80, "rajma": 180,
    "curd": 100, "chole": 200, "naan": 90, "chicken breast": 150, "spinach": 100,
    "khichdi": 250, "paneer": 100, "cucumber": 100, "fish": 150, "quinoa": 150,
    "chickpea": 150, "tomato": 80, "peanut butter": 20, "almonds": 30, "apple": 150,
    "yogurt": 150, "mango": 150, "protein powder": 30, "carrot": 80,
}


def suggest_meal(args: dict, db) -> dict:
    meal = str(args.get("meal", "")).strip().lower()
    combos = MEAL_SUGGESTIONS.get(meal)
    if not combos:
        return {"error": f"meal must be one of {list(MEAL_SUGGESTIONS)}"}

    since = time.time() - EXCLUDE_DAYS * 24 * 3600
    recent_names = {
        item["name"] for e in db.query_active("meals", "meal", since=since)
        for item in e["payload"]["items"]
    }
    fresh = [c for c in combos if not (set(c) & recent_names)]
    pool = fresh or combos  # if everything's been eaten recently, suggest anyway

    # day_start() is exactly 86400s apart between consecutive local midnights (no DST in
    # Asia/Kolkata), so this flips at local midnight rather than UTC midnight.
    day_index = int(clock.day_start() // 86400)
    combo = pool[day_index % len(pool)]

    items = []
    total = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for name in combo:
        grams = DEFAULT_GRAMS.get(name, 100)
        per100 = FOODS[name]
        m = dict(zip(("kcal", "protein", "carbs", "fat"), (v * grams / 100 for v in per100)))
        items.append({"name": name, "grams": grams, **{k: round(v, 1) for k, v in m.items()}})
        for k in total:
            total[k] += m[k]
    return {"meal": meal, "items": items, "total": {k: round(v, 1) for k, v in total.items()}}


USUAL_LOOKBACK_DAYS = 30


def usual_combo(meal: str, db) -> dict:
    """The most frequent exact combo (by food names) logged for this slot in the last 30
    days — used for the Today screen's one-tap "Usual" chip (ROADMAP-NEXTGEN.md P3). Grams
    come from that combo's most recent occurrence, not an average, so re-logging it matches
    what was actually eaten rather than a drifting blend. Deterministic: ties break on the
    combo's own name tuple, not insertion order, so results don't depend on dict iteration."""
    since = time.time() - USUAL_LOOKBACK_DAYS * 24 * 3600
    empty = {"meal": meal, "items": [], "total": {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
              "times_logged": 0}
    events = [e for e in db.query_active("meals", "meal", since=since)
              if e["payload"].get("meal") == meal and e["payload"].get("items")]
    if not events:
        return empty

    counts: Counter[tuple[str, ...]] = Counter()
    most_recent: dict[tuple[str, ...], dict] = {}
    for e in events:  # newest first (query_active's ORDER BY ts DESC)
        combo = tuple(sorted(item["name"] for item in e["payload"]["items"]))
        counts[combo] += 1
        most_recent.setdefault(combo, e["payload"])

    best = max(counts, key=lambda c: (counts[c], c))
    payload = most_recent[best]
    return {"meal": meal, "items": payload["items"], "total": payload["total"],
            "times_logged": counts[best]}


def usual_log_hour(meal: str, db) -> float | None:
    """Median local hour this slot has been logged at, over the last 30 days — lets the
    Today screen nudge "breakfast usually logged by now" instead of nagging at a fixed,
    one-size-fits-all time. None if the slot has no history yet to compare against."""
    since = time.time() - USUAL_LOOKBACK_DAYS * 24 * 3600
    hours = sorted(
        clock.local_hour(e["ts"]) for e in db.query_active("meals", "meal", since=since)
        if e["payload"].get("meal") == meal
    )
    if not hours:
        return None
    mid = len(hours) // 2
    if len(hours) % 2:
        return hours[mid]
    return (hours[mid - 1] + hours[mid]) / 2


def protein_gap_foods(db, limit: int = 3) -> list[str]:
    """2-3 of the user's own previously-eaten foods, ranked by protein density, to suggest
    for closing a protein shortfall — grounded in the food table (including the user's own
    recipes, P5) and their own history, never guessed by the model (ROADMAP-NEXTGEN.md P3)."""
    all_foods = {**FOODS, **_user_foods(db)}
    eaten = {
        item["name"] for e in db.query_active("meals", "meal", limit=10**6)
        for item in e["payload"]["items"]
    }
    eaten &= all_foods.keys()  # a food/recipe name logged in the past may no longer resolve
    ranked = sorted(eaten, key=lambda n: (-all_foods[n][1], n))
    return ranked[:limit]


module = Module(
    name="meals",
    version="0.1.0",
    system_prompt=(
        "Meals: identify each food. Prefer qty+unit in everyday terms the user actually said "
        "(e.g. 2 roti, 1 katori dal, 1 cup rice) over estimating grams yourself — the tool "
        "converts units to grams using a household-units table. Only give grams directly if "
        "the user stated an exact weight. Call meals__log_meal with items; macros are "
        "computed by the tool. Items the tool reports as unknown must be mentioned to the "
        "user. If the user describes a dish by its ingredients (e.g. 'my poha is 200g poha, "
        "1 egg, 50g peanuts') and names it, call define_recipe once so future mentions of "
        "that name match it directly."
    ),
    tools=[
        Tool(
            "log_meal",
            "Log a meal from identified foods, in qty+unit (preferred) or exact grams.",
            {"type": "object", "required": ["items"], "properties": {
                "meal": {"type": "string", "enum": ["breakfast", "lunch", "dinner", "snack"]},
                "items": {"type": "array", "items": {"type": "object",
                          "required": ["name"], "properties": {
                              "name": {"type": "string"},
                              "qty": {"type": "number",
                                      "description": "quantity in the given unit"},
                              "unit": {"type": "string",
                                       "description": "e.g. piece, katori, cup, bowl, tbsp, "
                                                       "tsp, slice, glass, gram"},
                              "grams": {"type": "number",
                                        "description": "exact grams, only if known directly"},
                          }}}}},
            log_meal,
        ),
        Tool("daily_summary", "Macro totals for today (local calendar day).",
             {"type": "object", "properties": {}}, daily_summary),
        Tool(
            "suggest_meal",
            "Suggest a meal combo for a meal type, avoiding anything eaten recently.",
            {"type": "object", "required": ["meal"], "properties": {
                "meal": {"type": "string", "enum": ["breakfast", "lunch", "dinner", "snack"]}}},
            suggest_meal,
        ),
        Tool(
            "define_recipe",
            "Define a named composite dish from its ingredients; macros are computed from "
            "the ingredient table, not guessed. Future log_meal calls can then use this name.",
            {"type": "object", "required": ["name", "ingredients"], "properties": {
                "name": {"type": "string"},
                "ingredients": {"type": "array", "items": {"type": "object",
                                 "required": ["name"], "properties": {
                                     "name": {"type": "string"},
                                     "grams": {"type": "number"},
                                     "qty": {"type": "number"},
                                     "unit": {"type": "string"},
                                 }}},
            }},
            define_recipe,
        ),
    ],
)


def edit_meal(args: dict, registry, db) -> dict:
    """Correct the grams of an already-logged meal (photo/chat result card). Logs the corrected
    meal first, then voids the original, so a failure never leaves the day with nothing."""
    event_id = args.get("event_id")
    items = args.get("items")
    old = db.get(event_id) if isinstance(event_id, int) else None
    if not old or old["module"] != "meals" or old["kind"] != "meal":
        return {"error": "event_id is not a logged meal"}
    if not isinstance(items, list) or not items:
        return {"error": "items must be a non-empty list"}
    new = log_meal({"meal": old["payload"].get("meal", "unspecified"), "items": items}, db)
    if "event_id" not in new:
        return {"error": "no items matched", **new}
    voided = registry.call("events__void_event", {"event_id": event_id}, db)
    if "error" in voided:
        return voided
    return {**new, "replaced": event_id}
