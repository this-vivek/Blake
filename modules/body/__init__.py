"""Body weight (ROADMAP-NEXTGEN.md P8): weigh-ins with a 7-day moving average.

One weigh-in counts per local day (the latest wins). The moving average is the mean of the
weigh-in days in the 7 calendar days ending on that day, which smooths day-to-day noise.
"""
import datetime as dt
from zoneinfo import ZoneInfo

from app.core import clock
from app.core.module import Module, Tool

MIN_KG, MAX_KG = 20.0, 300.0
BACKFILL_DAYS = 30
MAX_TREND_DAYS = 365


def _tz() -> ZoneInfo:
    return ZoneInfo(clock.DEFAULT_TZ)


def _date(ts: float) -> dt.date:
    return dt.datetime.fromtimestamp(ts, _tz()).date()


def _noon_ts(d: dt.date) -> float:
    return dt.datetime(d.year, d.month, d.day, 12, tzinfo=_tz()).timestamp()


def weigh_ins(db) -> dict[dt.date, float]:
    """kg per local date; if a day has several, the latest logged wins."""
    out: dict[dt.date, float] = {}
    for e in sorted(db.query_active("body", "weight", limit=10**6), key=lambda e: e["id"]):
        out[dt.date.fromisoformat(e["payload"]["date"])] = float(e["payload"]["kg"])
    return out


def moving_average(by_day: dict[dt.date, float], day: dt.date, window: int = 7) -> float:
    vals = [kg for d, kg in by_day.items() if 0 <= (day - d).days < window]
    return round(sum(vals) / len(vals), 1) if vals else 0.0


def trend_points(db, days: int, today: dt.date | None = None) -> list[dict]:
    """[{date, kg, avg7}] for weigh-in days inside the window, oldest first."""
    today = today or _date(clock.now())
    by_day = weigh_ins(db)
    start = today - dt.timedelta(days=days - 1)
    return [{"date": d.isoformat(), "kg": by_day[d], "avg7": moving_average(by_day, d)}
            for d in sorted(by_day) if start <= d <= today]


def log_weight(args: dict, db) -> dict:
    try:
        kg = round(float(args["kg"]), 1)
    except (KeyError, TypeError, ValueError):
        return {"error": "need kg"}
    if not MIN_KG <= kg <= MAX_KG:
        return {"error": f"kg must be between {MIN_KG:g} and {MAX_KG:g}"}

    today = _date(clock.now())
    day = today
    if args.get("date"):
        try:
            day = dt.date.fromisoformat(str(args["date"]))
        except ValueError:
            return {"error": "date must look like 2026-11-02"}
        if day > today or (today - day).days > BACKFILL_DAYS:
            return {"error": f"date must be within the last {BACKFILL_DAYS} days and not in the future"}

    client_id = args.get("client_id")
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:
            return {"logged": existing["payload"], "duplicate": True, "event_id": existing["id"]}

    entry = {"kg": kg, "date": day.isoformat()}
    ts = clock.now() if day == today else _noon_ts(day)
    event_id = db.append("body", "weight", entry, ts=ts, client_id=client_id)
    by_day = weigh_ins(db)
    return {"logged": entry, "event_id": event_id, "avg7": moving_average(by_day, day)}


def weight_trend(args: dict, db) -> dict:
    try:
        days = int(args.get("days", 30))
    except (TypeError, ValueError):
        return {"error": "days must be a number"}
    if not 1 <= days <= MAX_TREND_DAYS:
        return {"error": f"days must be between 1 and {MAX_TREND_DAYS}"}
    points = trend_points(db, days)
    out: dict = {"days": days, "points": points}
    if points:
        out["latest_kg"] = points[-1]["kg"]
        out["avg7"] = points[-1]["avg7"]
        out["change_kg"] = round(points[-1]["kg"] - points[0]["kg"], 1)
    return out


def daily_summary(args: dict, db) -> dict:
    today = _date(clock.now())
    by_day = weigh_ins(db)
    latest_day = max(by_day) if by_day else None
    return {"today": {
        "kg": by_day.get(today),
        "avg7": moving_average(by_day, today) if by_day else None,
        "latest_kg": by_day[latest_day] if latest_day else None,
        "latest_date": latest_day.isoformat() if latest_day else None,
    }}


module = Module(
    name="body",
    version="0.1.0",
    system_prompt=(
        "Body weight: call log_weight when the user reports their weight in kg, e.g. 'I weigh "
        "72.4 kg' = kg 72.4. Use weight_trend for questions about how their weight is changing. "
        "Averages and changes come from the tool, never estimate them."
    ),
    tools=[
        Tool(
            "log_weight",
            "Log a body weight in kg for today (or a past date). One weigh-in counts per day.",
            {"type": "object", "required": ["kg"], "properties": {
                "kg": {"type": "number"},
                "date": {"type": "string", "description": "YYYY-MM-DD, only to backfill a past day"},
            }},
            log_weight,
        ),
        Tool(
            "weight_trend",
            "Weigh-ins with a 7-day moving average and the change over the last N days (default 30).",
            {"type": "object", "properties": {"days": {"type": "integer"}}},
            weight_trend,
        ),
        Tool("daily_summary", "Today's weigh-in and the 7-day average.", {"type": "object", "properties": {}}, daily_summary),
    ],
)
