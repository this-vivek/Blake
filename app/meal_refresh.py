"""LLM-backed "refresh" for meal suggestions — a cheap Haiku call that picks *which* foods
to suggest for variety; macros always come from the same deterministic FOODS lookup as every
other meal tool, never from the model. Falls back to the plain deterministic suggest_meal on
any failure (bad/unparseable response, API error, timeout), so Refresh can never break.
"""
import json
import time

from app.core.router import FAST_MODEL
from modules.meals import DEFAULT_GRAMS, EXCLUDE_DAYS, FOODS, _match

SYSTEM_PROMPT = (
    "You pick a balanced meal combo from a fixed food list, for variety. "
    "Reply with ONLY a JSON array, no prose, no markdown fences, e.g. "
    '[{"name":"egg","grams":100},{"name":"poha","grams":200}]. '
    "2-4 items. Only use food names from the list you're given. Realistic gram portions."
)


def refresh_meal_suggestion(client, registry, db, meal: str) -> dict:
    combos_exist = meal in {"breakfast", "lunch", "dinner", "snack"}
    if not combos_exist:
        return {"error": "meal must be one of breakfast, lunch, dinner, snack"}

    since = time.time() - EXCLUDE_DAYS * 24 * 3600
    recent_names = {
        item["name"] for e in db.query_active("meals", "meal", since=since)
        for item in e["payload"]["items"]
    }

    try:
        available = sorted(set(FOODS) - recent_names) or sorted(FOODS)
        prompt = (
            f"Meal: {meal}. Available foods: {', '.join(available)}. "
            f"Avoid suggesting: {', '.join(sorted(recent_names)) or 'nothing, no restrictions'}."
        )
        resp = client.messages.create(
            model=FAST_MODEL, max_tokens=150,
            system=[{"type": "text", "text": SYSTEM_PROMPT,
                     "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": prompt}],
        )
        text = "".join(b.text for b in resp.content if b.type == "text").strip()
        raw_items = json.loads(text)
        if not isinstance(raw_items, list) or not raw_items:
            raise ValueError("empty or non-list response")

        items = []
        total = {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}
        for it in raw_items:
            matched = _match(str(it.get("name", "")).strip().lower())
            if not matched:
                continue
            name, per100 = matched
            grams = float(it.get("grams") or DEFAULT_GRAMS.get(name, 100))
            if grams <= 0:
                continue
            m = dict(zip(("kcal", "protein", "carbs", "fat"), (v * grams / 100 for v in per100)))
            items.append({"name": name, "grams": grams, **{k: round(v, 1) for k, v in m.items()}})
            for k in total:
                total[k] += m[k]
        if not items:
            raise ValueError("no items matched the known food table")

        return {"meal": meal, "items": items,
                "total": {k: round(v, 1) for k, v in total.items()}, "source": "llm"}
    except Exception:  # noqa: BLE001 - any failure falls back, Refresh must never break
        fallback = registry.call("meals__suggest_meal", {"meal": meal}, db)
        fallback["source"] = "fallback"
        return fallback
