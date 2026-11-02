"""Context for a chat turn (ROADMAP-NEXTGEN.md P6): the last few turns and a snapshot of today,
so a follow-up such as "make that 3 rotis" can be answered with a void of the earlier entry
plus a fresh log.

It goes into the user message, never the system prompt, so the system prompt and tool schemas
stay byte-identical between requests and prompt caching keeps working.
"""
from app.core import clock

MAX_TURNS = 3
TURN_WINDOW_S = 2 * 3600       # an older turn is a different conversation
MAX_FIELD = 240
MAX_MEALS = 6


def _clip(text: str, limit: int = MAX_FIELD) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _call_line(call: dict) -> str:
    out = call.get("output") or {}
    event_id = out.get("event_id")
    inp = call.get("input") or {}
    detail = ""
    if "items" in inp:
        detail = ", ".join(
            f"{i.get('name')} {i.get('qty', i.get('grams', ''))}{(' ' + str(i['unit'])) if i.get('unit') else ''}".strip()
            for i in inp["items"] if isinstance(i, dict)
        )
    elif "exercise" in inp:
        detail = f"{inp.get('exercise')} {inp.get('weight_kg')} kg x {inp.get('reps')}"
    elif "amount" in inp:
        detail = f"{inp.get('amount')} {inp.get('category', '')} {inp.get('note', '')}".strip()
    suffix = f" (event_id {event_id})" if event_id is not None else ""
    return f"{call.get('name')}: {_clip(detail)}{suffix}"


def _is_undone(db, call: dict, cache: dict[str, set]) -> bool:
    event_id = (call.get("output") or {}).get("event_id")
    if event_id is None:
        return False
    module = str(call.get("name", "")).split("__")[0]
    if module not in cache:
        cache[module] = {e["payload"].get("target_id") for e in db.query(module, "void", limit=10**6)}
    return event_id in cache[module]


def build_context(db, registry, now: float | None = None) -> str:
    """Plain text for the model. Empty when there is nothing useful to say."""
    now = clock.now() if now is None else now
    lines: list[str] = []

    voided: dict[str, set] = {}
    turns = [e for e in db.query_active("chat", "turn", since=now - TURN_WINDOW_S, limit=MAX_TURNS)]
    if turns:
        lines.append("Recent conversation (oldest first):")
        for e in reversed(turns):
            p = e["payload"]
            lines.append(f"- User: {_clip(p.get('text') or '(photo)')}")
            for call in p.get("calls", []):
                undone = _is_undone(db, call, voided)
                lines.append(f"  Logged -> {_call_line(call)}{' [already undone]' if undone else ''}")

    since = clock.day_start(at=now)
    meals = db.query_active("meals", "meal", since=since, limit=MAX_MEALS)
    totals = registry.call("meals__daily_summary", {}, db).get("today", {})
    if meals or totals.get("kcal"):
        lines.append(f"Today so far: {round(totals.get('kcal', 0))} kcal, {round(totals.get('protein', 0))} g protein.")
        for e in meals:
            p = e["payload"]
            items = ", ".join(f"{i['name']} {round(i['grams'])} g" for i in p.get("items", []))
            lines.append(f"- {p.get('meal', 'meal')}: {_clip(items)} (event_id {e['id']})")

    if not lines:
        return ""
    lines.append(
        "If the user is correcting or changing something above (for example 'make that 3 rotis'), "
        "void the earlier entry by its event_id with void_event, then log the corrected one."
    )
    return "\n".join(lines)


def build_content(text: str, image_block: dict | None, context: str) -> list[dict]:
    """The user message for a turn: optional photo, optional context, then what they said."""
    content: list[dict] = []
    if image_block:
        content.append(image_block)
    if context:
        content.append({"type": "text", "text": f"<context>\n{context}\n</context>"})
    content.append({"type": "text", "text": text or "Log this."})
    return content
