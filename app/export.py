"""Data export (ROADMAP-NEXTGEN.md P9): everything you logged, as JSON or as one flat CSV.

Only live data is exported: entries you undid, and the undo markers themselves, are left out.
Dates and times are local (BLAKE_TZ).
"""
import csv
import io
from datetime import datetime
from zoneinfo import ZoneInfo

from app.core import clock

# (module, kind) of every log entry that goes into the export
LOG_KINDS = [
    ("meals", "meal"), ("workouts", "set"), ("workouts", "warmup"), ("expenses", "expense"),
    ("tasks", "task"), ("tasks", "task_done"), ("habits", "habit_log"), ("body", "weight"), ("sleep", "sleep"),
]
CSV_COLUMNS = ["date", "time", "module", "kind", "title", "value", "unit", "details"]


def _num(v) -> str:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return ""
    return str(int(f)) if f == int(f) else f"{f:g}"


def _flat(module: str, kind: str, p: dict) -> dict | None:
    """The spreadsheet view of one entry: a title, a main number with its unit, and details."""
    if kind == "meal":
        items = "; ".join(f"{i.get('name')} {_num(i.get('grams'))} g" for i in p.get("items", []))
        return {"title": p.get("meal", "meal"), "value": _num((p.get("total") or {}).get("kcal")), "unit": "kcal", "details": items}
    if kind == "set":
        return {"title": p.get("exercise", ""), "value": _num(p.get("weight_kg")), "unit": "kg", "details": f"{p.get('reps')} reps"}
    if kind == "warmup":
        return {"title": p.get("name") or "warm-up", "value": _num(p.get("seconds")), "unit": "s",
                "details": f"{_num(p.get('kcal_burned'))} kcal burned"}
    if kind == "expense":
        loc = p.get("location")
        where = f" at {loc['lat']},{loc['lon']}" if loc else ""
        return {"title": p.get("note") or p.get("category", ""), "value": _num(p.get("amount")), "unit": "",
                "details": f"{p.get('category', '')}{where}"}
    if kind == "task":
        return {"title": p.get("title", ""), "value": "", "unit": "", "details": f"due {p['due']}" if p.get("due") else "added"}
    if kind == "task_done":
        return {"title": p.get("title", ""), "value": "", "unit": "", "details": "completed"}
    if kind == "habit_log":
        return {"title": p.get("name", ""), "value": _num(p.get("value")), "unit": "", "details": ""}
    if kind == "weight":
        return {"title": "weight", "value": _num(p.get("kg")), "unit": "kg", "details": ""}
    if kind == "sleep":
        return {"title": "sleep", "value": _num(p.get("hours")), "unit": "h",
                "details": f"quality {p['quality']} of 5" if p.get("quality") else ""}
    return None


def _entries(db) -> list[dict]:
    tz = ZoneInfo(clock.DEFAULT_TZ)
    rows = []
    for module, kind in LOG_KINDS:
        for e in db.query_active(module, kind, limit=10**7):
            local = datetime.fromtimestamp(e["ts"], tz)
            rows.append({"id": e["id"], "ts": e["ts"], "date": local.date().isoformat(),
                         "time": local.strftime("%H:%M"), "module": module, "kind": kind, "data": e["payload"]})
    rows.sort(key=lambda r: (r["ts"], r["id"]))
    return rows


def export_json(db, registry) -> dict:
    tz = ZoneInfo(clock.DEFAULT_TZ)
    return {
        "exported_at": datetime.now(tz).isoformat(timespec="seconds"),
        "timezone": clock.DEFAULT_TZ,
        "goals": registry.call("goals__get_goals", {}, db),
        "habits": sorted(registry.call("habits__habit_status", {}, db)["habits"], key=lambda h: h["name"]),
        "custom_foods": [e["payload"] for e in db.query_active("meals", "custom_food", limit=10**6)],
        "recipes": [e["payload"] for e in db.query_active("meals", "recipe", limit=10**6)],
        "entries": _entries(db),
    }


def export_csv(db) -> str:
    out = io.StringIO()
    writer = csv.DictWriter(out, fieldnames=CSV_COLUMNS, lineterminator="\n")
    writer.writeheader()
    for r in _entries(db):
        flat = _flat(r["module"], r["kind"], r["data"])
        if flat is None:
            continue
        writer.writerow({"date": r["date"], "time": r["time"], "module": r["module"], "kind": r["kind"], **flat})
    return out.getvalue()
