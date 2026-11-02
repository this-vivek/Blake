"""Sleep (ROADMAP-NEXTGEN.md P8): manual duration plus a 1-5 quality rating.

A night is filed under the day you woke up. One entry counts per day (the latest wins).
"""
import datetime as dt
from zoneinfo import ZoneInfo

from app.core import clock
from app.core.module import Module, Tool

MAX_HOURS = 24.0
BACKFILL_DAYS = 30
MAX_SUMMARY_DAYS = 90


def _tz() -> ZoneInfo:
    return ZoneInfo(clock.DEFAULT_TZ)


def _date(ts: float) -> dt.date:
    return dt.datetime.fromtimestamp(ts, _tz()).date()


def _noon_ts(d: dt.date) -> float:
    return dt.datetime(d.year, d.month, d.day, 12, tzinfo=_tz()).timestamp()


def nights(db) -> dict[dt.date, dict]:
    """{wake date: {hours, quality|None}}; the latest entry for a day wins."""
    out: dict[dt.date, dict] = {}
    for e in sorted(db.query_active("sleep", "sleep", limit=10**6), key=lambda e: e["id"]):
        p = e["payload"]
        out[dt.date.fromisoformat(p["date"])] = {"hours": float(p["hours"]), "quality": p.get("quality")}
    return out


def points(db, days: int, today: dt.date | None = None) -> list[dict]:
    today = today or _date(clock.now())
    start = today - dt.timedelta(days=days - 1)
    return [{"date": d.isoformat(), **v} for d, v in sorted(nights(db).items()) if start <= d <= today]


def log_sleep(args: dict, db) -> dict:
    try:
        hours = round(float(args["hours"]), 2)
    except (KeyError, TypeError, ValueError):
        return {"error": "need hours"}
    if not 0 < hours <= MAX_HOURS:
        return {"error": f"hours must be between 0 and {MAX_HOURS:g}"}
    quality = args.get("quality")
    if quality is not None:
        try:
            quality = int(quality)
        except (TypeError, ValueError):
            return {"error": "quality must be a whole number from 1 to 5"}
        if not 1 <= quality <= 5:
            return {"error": "quality must be a whole number from 1 to 5"}

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

    entry = {"hours": hours, "quality": quality, "date": day.isoformat()}
    ts = clock.now() if day == today else _noon_ts(day)
    event_id = db.append("sleep", "sleep", entry, ts=ts, client_id=client_id)
    return {"logged": entry, "event_id": event_id}


def sleep_summary(args: dict, db) -> dict:
    try:
        days = int(args.get("days", 7))
    except (TypeError, ValueError):
        return {"error": "days must be a number"}
    if not 1 <= days <= MAX_SUMMARY_DAYS:
        return {"error": f"days must be between 1 and {MAX_SUMMARY_DAYS}"}
    pts = points(db, days)
    out: dict = {"days": days, "nights_logged": len(pts), "points": pts}
    if pts:
        out["avg_hours"] = round(sum(p["hours"] for p in pts) / len(pts), 1)
        rated = [p["quality"] for p in pts if p["quality"]]
        if rated:
            out["avg_quality"] = round(sum(rated) / len(rated), 1)
    return out


def daily_summary(args: dict, db) -> dict:
    last = nights(db).get(_date(clock.now()))
    return {"today": last}


module = Module(
    name="sleep",
    version="0.1.0",
    system_prompt=(
        "Sleep: call log_sleep when the user reports how long they slept, with an optional "
        "quality from 1 (bad) to 5 (great) only if they gave one, e.g. 'slept 7 hours, pretty "
        "good' = hours 7. A night belongs to the day they woke up. Use sleep_summary for "
        "questions about recent sleep; averages come from the tool."
    ),
    tools=[
        Tool(
            "log_sleep",
            "Log last night's sleep: hours, and an optional quality from 1 to 5.",
            {"type": "object", "required": ["hours"], "properties": {
                "hours": {"type": "number"},
                "quality": {"type": "integer", "description": "1 (bad) to 5 (great), only if stated"},
                "date": {"type": "string", "description": "wake-up date YYYY-MM-DD, only to backfill"},
            }},
            log_sleep,
        ),
        Tool(
            "sleep_summary",
            "Average sleep hours and quality over the last N days (default 7).",
            {"type": "object", "properties": {"days": {"type": "integer"}}},
            sleep_summary,
        ),
        Tool("daily_summary", "This morning's sleep entry, if logged.", {"type": "object", "properties": {}}, daily_summary),
    ],
)
