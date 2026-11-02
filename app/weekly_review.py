"""Weekly review (ROADMAP-NEXTGEN.md P6).

Code builds a packet of numbers for the last completed Monday-to-Sunday week; Sonnet turns it
into at most 120 words and up to 3 focus items. The write-up may only use numbers that are in
the packet: anything else is rejected, retried once, and finally replaced by a plain text
written by code from the same packet, so a wrong number can never reach the screen.

No cron: the review is created on the first request after the week closes and saved as an
event, so every later request returns the same text.
"""
import json
import re
from datetime import timedelta

from app.core import clock
from app.core.router import SMART_MODEL
from app.insights import _hit, _tz, daily_totals, local_date, streaks
from modules.workouts import prs_in

MAX_WORDS = 120
MAX_FOCUS = 3
NUMBER_RE = re.compile(r"\d[\d,]*(?:\.\d+)?")

SYSTEM_PROMPT = (
    "You write a short weekly review for one person who tracks food, training and spending. "
    "You are given a JSON packet of numbers for last week. Use ONLY numbers that appear in the "
    "packet. Never invent, add, subtract, convert or estimate a number, and never mention dates "
    "or week numbers. Write at most 120 words of plain prose: what went well, what slipped, "
    "calm and specific, no emoji, no exclamation marks. Then give at most 3 short focus items "
    "for next week. Reply with ONLY JSON, no markdown: "
    '{"summary": "...", "focus": ["...", "..."]}.'
)


# ---- the packet -------------------------------------------------------------------------------

def build_packet(db, registry, week_start, tz) -> dict:
    dates = [week_start + timedelta(days=i) for i in range(7)]
    totals = daily_totals(db, dates, tz)
    goals = registry.call("goals__get_goals", {}, db)

    logged = [d for d in dates if totals[d]["meals"] > 0]
    kcal_goal, protein_goal, spend_goal = goals.get("kcal"), goals.get("protein_g"), goals.get("daily_spend")
    workout_goal = goals.get("workout_days_per_week")

    def avg(key):
        vals = [totals[d][key] for d in logged]
        return round(sum(vals) / len(vals)) if vals else 0

    packet: dict = {"days_in_week": 7, "days_logged_meals": len(logged)}
    packet["kcal"] = {"avg": avg("kcal")}
    if kcal_goal:
        on = sum(1 for d in dates if _hit("kcal", totals[d], kcal_goal))
        packet["kcal"].update({"goal": round(kcal_goal), "days_on_target": on})
    packet["protein_g"] = {"avg": avg("protein")}
    if protein_goal:
        hit = sum(1 for d in dates if _hit("protein", totals[d], protein_goal))
        packet["protein_g"].update({"goal": round(protein_goal), "days_hit": hit})
    spend_total = sum(totals[d]["spend"] for d in dates)
    packet["spend"] = {"total": round(spend_total)}
    if spend_goal:
        over = sum(1 for d in dates if totals[d]["spend"] > spend_goal)
        packet["spend"].update({"daily_budget": round(spend_goal), "days_over_budget": over})

    training_days = sum(1 for d in dates if totals[d]["sets"])
    packet["training"] = {"days": training_days, "sets": sum(totals[d]["sets"] for d in dates)}
    if workout_goal:
        packet["training"]["goal_days"] = int(workout_goal)
    volume: dict[str, float] = {}
    for d in dates:
        for group, kg in totals[d]["volume"].items():
            volume[group] = volume.get(group, 0.0) + kg
    packet["volume_kg_by_group"] = {g: round(v) for g, v in sorted(volume.items())}

    end_ts = _day_start_ts(week_start + timedelta(days=7), tz)
    start_ts = _day_start_ts(week_start, tz)
    all_sets = sorted(
        (e for e in db.query_active("workouts", "set", limit=10**6) if e["ts"] < end_ts),
        key=lambda e: e["ts"],
    )
    packet["personal_records"] = [
        {"exercise": p["exercise"], "e1rm_kg": round(p["e1rm"])} for p in prs_in(all_sets, start_ts)
    ]
    packet["personal_record_count"] = len(packet["personal_records"])

    # Streaks as they stood at the end of that week.
    s = streaks(db, registry, now=end_ts - 1, tz_name=str(tz))
    packet["streaks"] = {
        "meals_logged_days": (s["meals_logged"] or {}).get("current", 0),
        "training_weeks": (s["training"] or {}).get("current_weeks", 0),
    }
    return packet


def _day_start_ts(d, tz) -> float:
    from datetime import datetime
    return datetime(d.year, d.month, d.day, tzinfo=tz).timestamp()


def _has_activity(packet: dict) -> bool:
    return bool(packet["days_logged_meals"] or packet["training"]["days"] or packet["spend"]["total"])


# ---- number safety -----------------------------------------------------------------------------

def _numbers_in(value) -> set[float]:
    out: set[float] = set()
    if isinstance(value, bool):
        return out
    if isinstance(value, (int, float)):
        out.add(float(value))
        out.add(float(round(value)))
        out.add(round(float(value), 1))
    elif isinstance(value, dict):
        for v in value.values():
            out |= _numbers_in(v)
    elif isinstance(value, list):
        for v in value:
            out |= _numbers_in(v)
    return out


def numbers_outside_packet(text: str, packet: dict) -> list[str]:
    """Every number written in `text` that is not in the packet (thousands commas ignored)."""
    allowed = _numbers_in(packet)
    bad = []
    for raw in NUMBER_RE.findall(text):
        value = float(raw.replace(",", ""))
        if not any(abs(value - a) < 0.05 for a in allowed):
            bad.append(raw)
    return bad


def _validate(parsed, packet: dict) -> tuple[dict | None, str]:
    if not isinstance(parsed, dict):
        return None, "reply was not a JSON object"
    summary = str(parsed.get("summary", "")).strip()
    focus = parsed.get("focus", [])
    if not summary or not isinstance(focus, list):
        return None, "need a summary and a focus list"
    focus = [str(f).strip() for f in focus if str(f).strip()][:MAX_FOCUS]
    if len(summary.split()) > MAX_WORDS:
        return None, f"summary has more than {MAX_WORDS} words"
    bad = numbers_outside_packet(" ".join([summary, *focus]), packet)
    if bad:
        return None, f"these numbers are not in the data: {', '.join(bad)}"
    return {"summary": summary, "focus": focus}, ""


def _ask_model(client, packet: dict, feedback: str = "") -> str:
    content = "Last week's data:\n" + json.dumps(packet, indent=1)
    if feedback:
        content += f"\n\nYour previous answer was rejected: {feedback}. Try again using only the data above."
    resp = client.messages.create(
        model=SMART_MODEL, max_tokens=500,
        system=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": content}],
    )
    return "".join(b.text for b in resp.content if b.type == "text").strip()


def _parse_json(text: str):
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    return json.loads(text)


def write_with_model(client, packet: dict) -> dict | None:
    feedback = ""
    for _attempt in range(2):
        try:
            parsed = _parse_json(_ask_model(client, packet, feedback))
        except Exception as e:  # noqa: BLE001 - API or JSON failure: retry once, then fall back
            feedback = f"it could not be used ({type(e).__name__})"
            continue
        result, feedback = _validate(parsed, packet)
        if result:
            return result
    return None


# ---- plain-text fallback, built only from the packet ------------------------------------------

def write_fallback(packet: dict) -> dict:
    t, k, p, sp = packet["training"], packet["kcal"], packet["protein_g"], packet["spend"]
    parts = [f"You logged meals on {packet['days_logged_meals']} of {packet['days_in_week']} days"
             f", averaging {k['avg']} kcal and {p['avg']} g of protein."]
    if "days_on_target" in k:
        parts.append(f"Calories were on target on {k['days_on_target']} days.")
    parts.append(f"You trained on {t['days']} days"
                 + (f" against a goal of {t['goal_days']}." if "goal_days" in t else "."))
    parts.append(f"You spent {sp['total']} in total.")
    if packet["personal_record_count"]:
        parts.append(f"You set {packet['personal_record_count']} personal record"
                     + ("s." if packet["personal_record_count"] != 1 else "."))
    focus = []
    if "goal" in p and p["avg"] < p["goal"]:
        focus.append(f"Raise protein toward {p['goal']} g a day.")
    if "goal_days" in t and t["days"] < t["goal_days"]:
        focus.append(f"Aim for {t['goal_days']} training days.")
    if packet["days_logged_meals"] < packet["days_in_week"]:
        focus.append("Log your meals every day, even the small ones.")
    if "days_over_budget" in sp and sp["days_over_budget"]:
        focus.append(f"Stay within {sp['daily_budget']} a day; you went over on {sp['days_over_budget']} days.")
    return {"summary": " ".join(parts), "focus": focus[:MAX_FOCUS]}


# ---- entry point --------------------------------------------------------------------------------

def weekly_review(db, registry, client, now: float | None = None, tz_name: str | None = None) -> dict:
    tz = _tz(tz_name)
    now = clock.now() if now is None else now
    today = local_date(now, tz)
    week_start = today - timedelta(days=today.weekday() + 7)  # Monday of the last completed week
    key = week_start.isoformat()

    for e in db.query_active("insights", "weekly_review", limit=1000):
        if e["payload"].get("week_start") == key:
            return e["payload"]

    packet = build_packet(db, registry, week_start, tz)
    if not _has_activity(packet):
        return {"week_start": key, "empty": True, "message": "Nothing was logged last week."}

    written = write_with_model(client, packet)
    source = "sonnet"
    if written is None:
        written, source = write_fallback(packet), "fallback"
    payload = {"week_start": key, "packet": packet, **written, "source": source, "generated_at": now}
    db.append("insights", "weekly_review", payload, ts=now)
    return payload
