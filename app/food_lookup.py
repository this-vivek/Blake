"""Look up per-100 g nutrition for a food that is not in the table yet.

This runs only when you ask for it on the Unmatched screen, never inside meal logging, so
logging stays fast, offline-safe and deterministic. Nothing found here is stored: it comes
back as a *proposal* with its source, and only your approval (POST /meals/custom_food) puts
it in the table.

Order: USDA FoodData Central (measured data) first; if that finds nothing usable, a Sonnet
estimate that is clearly labelled as unverified.
"""
import json
import os
import urllib.parse
import urllib.request

from app.core.router import SMART_MODEL

USDA_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"
USDA_TIMEOUT_S = 8
# Per-100 g datasets only (branded foods report per serving and are noisy).
USDA_DATA_TYPES = "Foundation,SR Legacy"

MACROS = ("kcal", "protein", "carbs", "fat")

MODEL_SYSTEM_PROMPT = (
    "You estimate typical nutrition per 100 g for one food, as normally eaten (cooked if it "
    "is normally cooked). Reply with ONLY a JSON object, no prose, no markdown, exactly: "
    '{"kcal": number, "protein": number, "carbs": number, "fat": number}. '
    "Grams of protein, carbs and fat per 100 g; kcal per 100 g."
)


def _default_fetch(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=USDA_TIMEOUT_S) as resp:  # noqa: S310 - fixed https host
        return json.loads(resp.read().decode("utf-8"))


def valid_per100(per100: dict, check_energy: bool = False) -> bool:
    """Sanity bounds for per-100 g values. check_energy also requires kcal to roughly match
    4/4/9 kcal per gram of macros, which catches made-up model numbers."""
    try:
        v = {k: float(per100[k]) for k in MACROS}
    except (KeyError, TypeError, ValueError):
        return False
    if any(x < 0 for x in v.values()) or v["kcal"] > 900:
        return False
    if v["protein"] + v["carbs"] + v["fat"] > 100.5:
        return False
    if check_energy:
        implied = 4 * v["protein"] + 4 * v["carbs"] + 9 * v["fat"]
        if abs(v["kcal"] - implied) > max(40.0, 0.35 * max(v["kcal"], implied)):
            return False
    return True


def _usda_values(food: dict) -> dict | None:
    out: dict[str, float] = {}
    for n in food.get("foodNutrients", []):
        name = str(n.get("nutrientName", "")).lower()
        unit = str(n.get("unitName", "")).upper()
        value = n.get("value")
        if value is None:
            continue
        if name.startswith("energy") and unit == "KCAL":
            out.setdefault("kcal", float(value))
        elif name == "protein":
            out["protein"] = float(value)
        elif name.startswith("carbohydrate"):
            out["carbs"] = float(value)
        elif name.startswith("total lipid"):
            out["fat"] = float(value)
    return out if all(k in out for k in MACROS) else None


# Words that mean the entry is a processed product rather than the plain food.
_PROCESSED = {
    "souffle", "candied", "dried", "dehydrated", "powder", "canned", "frozen", "juice", "oil",
    "soup", "sauce", "fried", "pickled", "spices", "spice", "seeds", "flour", "baby", "babyfood",
    "imitation", "drink", "beverage", "cake", "pie", "bread", "sweetened", "syrup", "salad",
    "dressing", "pudding", "snack", "bar", "cereal", "cookie", "cookies", "chips", "crackers",
}


def _tokens(text: str) -> list[str]:
    """Lowercase words with a trailing plural 's' removed ("peppers" matches "pepper")."""
    words = [w.strip("(),.;:") for w in text.lower().replace("-", " ").split()]
    return [w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w
            for w in words if w]


def _score(query_tokens: list[str], description: str) -> float | None:
    """How well a USDA description is the plain food named by the query; None if it isn't."""
    desc_tokens = _tokens(description)
    if not query_tokens or not all(q in desc_tokens for q in query_tokens):
        return None
    head = _tokens(description.split(",")[0])
    score = 0.0
    if all(q in head for q in query_tokens):
        score += 10  # the entry is *about* this food: "Spinach, raw", "Peppers, bell, raw"
    if "raw" in desc_tokens:
        score += 3
    score -= 5 * sum(1 for t in desc_tokens if t in _PROCESSED)
    score -= 0.2 * len(desc_tokens)
    return score


def _query_variants(name: str) -> list[str]:
    from modules.meals import FOOD_ALIASES  # local import: modules/meals imports app.core

    variants = [name]
    aliased = " ".join(FOOD_ALIASES.get(w, w) for w in name.split())
    if aliased != name:
        variants.append(aliased)
    return variants


def lookup_usda(name: str, fetch=None, api_key: str | None = None) -> dict | None:
    """Best USDA match that is really the named food (not a soufflé or a spice), or None."""
    key = api_key or os.getenv("USDA_API_KEY") or "DEMO_KEY"
    for variant in _query_variants(name):
        query = urllib.parse.urlencode({
            "query": variant, "dataType": USDA_DATA_TYPES, "pageSize": 25, "api_key": key,
        })
        try:
            data = (fetch or _default_fetch)(f"{USDA_SEARCH_URL}?{query}")
        except Exception:  # noqa: BLE001 - any network/JSON failure just means "no USDA result"
            return None
        q_tokens = _tokens(variant)
        best, best_score = None, None
        for food in data.get("foods", []):
            per100 = _usda_values(food)
            if not per100 or not valid_per100(per100):
                continue
            score = _score(q_tokens, str(food.get("description", "")))
            if score is not None and (best_score is None or score > best_score):
                best, best_score = (food, per100), score
        if best:
            food, per100 = best
            return {
                "name": name,
                "per100": {k: round(per100[k], 1) for k in MACROS},
                "source": f"USDA FoodData Central: {food.get('description', name)} (fdcId {food.get('fdcId')})",
                "origin": "usda",
                "proposed": True,
            }
    return None


def propose_with_model(client, name: str) -> dict | None:
    """A labelled guess from Sonnet. Returned only if it parses and passes the sanity checks."""
    try:
        resp = client.messages.create(
            model=SMART_MODEL, max_tokens=120,
            system=[{"type": "text", "text": MODEL_SYSTEM_PROMPT,
                     "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": f"Food: {name}"}],
        )
        text = "".join(b.text for b in resp.content if b.type == "text").strip()
        per100 = json.loads(text)
    except Exception:  # noqa: BLE001 - API error or unparseable reply: no proposal
        return None
    if not isinstance(per100, dict) or not valid_per100(per100, check_energy=True):
        return None
    return {
        "name": name,
        "per100": {k: round(float(per100[k]), 1) for k in MACROS},
        "source": "AI estimate (Sonnet), unverified. Check before approving.",
        "origin": "model",
        "proposed": True,
    }


def propose_food(client, name: str, fetch=None) -> dict:
    name = str(name).strip().lower()
    if not name:
        return {"error": "name is required"}
    proposal = lookup_usda(name, fetch=fetch) or propose_with_model(client, name)
    if proposal is None:
        return {"error": f"couldn't find values for '{name}'. Enter them by hand."}
    return {"proposal": proposal}
