"""Habits (ROADMAP-NEXTGEN.md P8): yes/no or count habits with streaks that forgive one missed
day a week. Water tracking lives here as a built-in count habit called "water".

Events: `habit` (a definition, the latest per name wins), `habit_archive` (hides a habit; its
history stays), `habit_log` (name, value, local date). Undo is the usual void event.
"""
import datetime as dt
from zoneinfo import ZoneInfo

from app.core import clock
from app.core.module import Module, Tool

KINDS = ("yesno", "count")
MAX_NAME = 40
MAX_COUNT_PER_LOG = 1000
BACKFILL_DAYS = 30
WATER = {"name": "water", "kind": "count", "target": 8.0, "unit": "glass"}


def _tz() -> ZoneInfo:
    return ZoneInfo(clock.DEFAULT_TZ)


def _date(ts: float) -> dt.date:
    return dt.datetime.fromtimestamp(ts, _tz()).date()


def _noon_ts(d: dt.date) -> float:
    return dt.datetime(d.year, d.month, d.day, 12, tzinfo=_tz()).timestamp()


def definitions(db) -> dict[str, dict]:
    """The current habits by name: latest definition wins, archived ones are gone."""
    events = sorted(
        db.query_active("habits", "habit", limit=10**6) + db.query_active("habits", "habit_archive", limit=10**6),
        key=lambda e: e["id"],
    )
    defs: dict[str, dict] = {}
    for e in events:
        name = e["payload"]["name"]
        if e["kind"] == "habit":
            defs[name] = e["payload"]
        else:
            defs.pop(name, None)
    return defs


def daily_values(db, name: str, since: dt.date | None = None) -> dict[dt.date, float]:
    """Total logged per local date for one habit."""
    totals: dict[dt.date, float] = {}
    for e in db.query_active("habits", "habit_log", limit=10**6):
        p = e["payload"]
        if p.get("name") != name:
            continue
        d = dt.date.fromisoformat(p["date"])
        if since is None or d >= since:
            totals[d] = totals.get(d, 0.0) + float(p.get("value", 1))
    return totals


def is_done(habit: dict, value: float) -> bool:
    if habit["kind"] == "yesno":
        return value > 0
    return value >= habit.get("target", 1)


def streak(habit: dict, values: dict[dt.date, float], today: dt.date) -> dict:
    """Current and best run of done days. One missed day per ISO week is forgiven; today does
    not count against you until it is over."""
    if not values:
        return {"current": 0, "best": 0, "grace_used_this_week": False}
    day = min(values)
    history = []
    while day <= today:
        history.append((day, is_done(habit, values.get(day, 0.0))))
        day += dt.timedelta(days=1)
    if not history[-1][1]:
        history = history[:-1]
    current = best = 0
    grace_weeks: set = set()
    for d, hit in history:
        if hit:
            current += 1
        else:
            week = d.isocalendar()[:2]
            if week not in grace_weeks:
                grace_weeks.add(week)
            else:
                current = 0
        best = max(best, current)
    return {"current": current, "best": best,
            "grace_used_this_week": today.isocalendar()[:2] in grace_weeks}


def _clean_name(raw) -> str:
    return " ".join(str(raw or "").lower().split())[:MAX_NAME]


def create_habit(args: dict, db) -> dict:
    name = _clean_name(args.get("name"))
    if not name:
        return {"error": "name is required"}
    kind = str(args.get("kind", "yesno")).lower()
    if kind not in KINDS:
        return {"error": f"kind must be one of {', '.join(KINDS)}"}
    habit = {"name": name, "kind": kind}
    if kind == "count":
        try:
            target = float(args.get("target", 1))
        except (TypeError, ValueError):
            return {"error": "target must be a number"}
        if target <= 0:
            return {"error": "target must be positive"}
        habit["target"] = target
        habit["unit"] = str(args.get("unit") or "").strip().lower()[:20]
    existing = definitions(db).get(name)
    if existing == habit:
        return {"habit": habit, "created": False}
    event_id = db.append("habits", "habit", habit)
    return {"habit": habit, "created": existing is None, "updated": existing is not None, "event_id": event_id}


def archive_habit(args: dict, db) -> dict:
    name = _clean_name(args.get("name"))
    if name not in definitions(db):
        return {"error": f"no habit called '{name}'"}
    db.append("habits", "habit_archive", {"name": name})
    return {"archived": name}


def log_habit(args: dict, db) -> dict:
    name = _clean_name(args.get("name"))
    defs = definitions(db)
    if name == WATER["name"] and name not in defs:
        db.append("habits", "habit", dict(WATER))
        defs = definitions(db)
    habit = defs.get(name)
    if not habit:
        return {"error": f"no habit called '{name}'. Create it first.", "known_habits": sorted(defs)}

    if habit["kind"] == "yesno":
        value = 1.0
    else:
        try:
            value = float(args.get("value", 1))
        except (TypeError, ValueError):
            return {"error": "value must be a number"}
        if not 0 < value <= MAX_COUNT_PER_LOG:
            return {"error": f"value must be between 0 and {MAX_COUNT_PER_LOG}"}

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
        if existing:  # retried request that already landed
            return {"logged": existing["payload"], "duplicate": True, "event_id": existing["id"]}

    ts = clock.now() if day == today else _noon_ts(day)
    entry = {"name": name, "value": value, "date": day.isoformat()}
    event_id = db.append("habits", "habit_log", entry, ts=ts, client_id=client_id)
    total = daily_values(db, name, since=day).get(day, 0.0)
    out = {"logged": entry, "event_id": event_id, "day_total": total, "done": is_done(habit, total)}
    if habit["kind"] == "count":
        out["target"] = habit["target"]
        out["unit"] = habit.get("unit", "")
    return out


def habit_status(args: dict, db) -> dict:
    today = _date(clock.now())
    items = []
    for name, habit in sorted(definitions(db).items()):
        values = daily_values(db, name)
        value = values.get(today, 0.0)
        item = {"name": name, "kind": habit["kind"], "value": value, "done": is_done(habit, value),
                "streak": streak(habit, values, today)}
        if habit["kind"] == "count":
            item["target"] = habit["target"]
            item["unit"] = habit.get("unit", "")
        items.append(item)
    return {"habits": items}


def daily_summary(args: dict, db) -> dict:
    return {"today": habit_status({}, db)["habits"]}


module = Module(
    name="habits",
    version="0.1.0",
    system_prompt=(
        "Habits: yes/no or count habits, with water as a built-in count habit (8 glasses a day). "
        "Call log_habit when the user reports doing one, e.g. 'drank 2 glasses of water' = name "
        "water, value 2; 'I meditated' = name meditation. If the habit does not exist and the user "
        "clearly wants to track it, call create_habit first, then log_habit. Streaks come from "
        "habit_status, never estimate them."
    ),
    tools=[
        Tool(
            "create_habit",
            "Create or update a habit to track. kind is yesno or count; count habits have a daily target.",
            {"type": "object", "required": ["name"], "properties": {
                "name": {"type": "string"},
                "kind": {"type": "string", "enum": list(KINDS)},
                "target": {"type": "number", "description": "daily target for count habits"},
                "unit": {"type": "string", "description": "e.g. glass, page, minute"},
            }},
            create_habit,
        ),
        Tool(
            "log_habit",
            "Log doing a habit today (or on a past date). Count habits add value to the day's total.",
            {"type": "object", "required": ["name"], "properties": {
                "name": {"type": "string"},
                "value": {"type": "number", "description": "amount for count habits, default 1"},
                "date": {"type": "string", "description": "YYYY-MM-DD, only to backfill a past day"},
            }},
            log_habit,
        ),
        Tool(
            "habit_status",
            "Today's progress and streak for every habit.",
            {"type": "object", "properties": {}},
            habit_status,
        ),
        Tool(
            "archive_habit",
            "Stop tracking a habit (its history is kept).",
            {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}},
            archive_habit,
        ),
        Tool("daily_summary", "Today's habit progress.", {"type": "object", "properties": {}}, daily_summary),
    ],
)
