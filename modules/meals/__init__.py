import re
import time

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
            if raw_name:
                db.append("meals", "unmatched", {"name": raw_name, "grams": grams})
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
    since = time.time() - 24 * 3600
    tot = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
    for e in db.query("meals", "meal", since=since):
        for k in tot:
            tot[k] += e["payload"]["total"][k]
    return {"last_24h": {k: round(v, 1) for k, v in tot.items()}}


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
        item["name"] for e in db.query("meals", "meal", since=since)
        for item in e["payload"]["items"]
    }
    fresh = [c for c in combos if not (set(c) & recent_names)]
    pool = fresh or combos  # if everything's been eaten recently, suggest anyway

    day_index = int(time.time() // 86400)
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
        Tool(
            "suggest_meal",
            "Suggest a meal combo for a meal type, avoiding anything eaten recently.",
            {"type": "object", "required": ["meal"], "properties": {
                "meal": {"type": "string", "enum": ["breakfast", "lunch", "dinner", "snack"]}}},
            suggest_meal,
        ),
    ],
)
