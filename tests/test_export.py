import csv
import io
import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.export import CSV_COLUMNS, export_csv, export_json

IST = ZoneInfo("Asia/Kolkata")


def at(day, hh=13, mm=0):
    y, m, d = (int(x) for x in day.split("-"))
    return datetime(y, m, d, hh, mm, tzinfo=IST).timestamp()


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


@pytest.fixture
def filled(reg, db):
    reg.call("goals__set_goal", {"kcal": 2000}, db)
    db.append("meals", "meal", {"meal": "dinner", "items": [{"name": "rice", "grams": 150}, {"name": "dal", "grams": 200}],
              "total": {"kcal": 612.4, "protein": 20, "carbs": 90, "fat": 8}}, ts=at("2026-11-03", 23, 30))
    db.append("workouts", "set", {"exercise": "bench press", "weight_kg": 60, "reps": 8, "e1rm": 76, "kcal_burned": 5}, ts=at("2026-11-04"))
    db.append("workouts", "warmup", {"seconds": 30, "kcal_burned": 1.5, "name": "Arm circles"}, ts=at("2026-11-04", 9))
    db.append("expenses", "expense", {"amount": 180.0, "category": "transport", "note": "Auto to office",
              "location": {"lat": 12.97, "lon": 77.59}}, ts=at("2026-11-04", 10))
    db.append("tasks", "task", {"title": "Renew gym", "due": "2026-11-06"}, ts=at("2026-11-04", 11))
    db.append("body", "weight", {"kg": 72.4, "date": "2026-11-04"}, ts=at("2026-11-04", 7))
    db.append("sleep", "sleep", {"hours": 7.5, "quality": 4, "date": "2026-11-04"}, ts=at("2026-11-04", 6))
    reg.call("habits__log_habit", {"name": "water", "value": 3}, db)
    return db


def rows(text):
    return list(csv.DictReader(io.StringIO(text)))


def test_csv_has_one_row_per_entry_with_local_dates_and_readable_columns(filled):
    out = rows(export_csv(filled))
    assert list(out[0]) == CSV_COLUMNS
    by_kind = {r["kind"]: r for r in out}
    meal = by_kind["meal"]
    assert (meal["date"], meal["time"]) == ("2026-11-03", "23:30")  # a late dinner stays on its own day
    assert meal["title"] == "dinner" and meal["value"] == "612.4" and meal["unit"] == "kcal"
    assert meal["details"] == "rice 150 g; dal 200 g"
    assert by_kind["set"]["details"] == "8 reps" and by_kind["set"]["value"] == "60"
    assert by_kind["expense"]["title"] == "Auto to office" and "12.97,77.59" in by_kind["expense"]["details"]
    assert by_kind["task"]["details"] == "due 2026-11-06"
    assert by_kind["sleep"]["details"] == "quality 4 of 5" and by_kind["weight"]["value"] == "72.4"
    assert by_kind["warmup"]["title"] == "Arm circles"


def test_rows_are_in_time_order(filled):
    stamps = [(r["date"], r["time"]) for r in rows(export_csv(filled))]
    assert stamps == sorted(stamps)


def test_undone_entries_and_void_markers_are_left_out(reg, filled):
    event_id = reg.call("expenses__log_expense", {"amount": 99, "category": "food"}, filled)["event_id"]
    reg.call("events__void_event", {"event_id": event_id}, filled)
    assert "99" not in [r["value"] for r in rows(export_csv(filled)) if r["module"] == "expenses"]
    data = export_json(filled, reg)
    assert event_id not in [e["id"] for e in data["entries"]]
    assert all(e["kind"] != "void" for e in data["entries"])


def test_json_includes_settings_as_well_as_entries(reg, filled):
    data = export_json(filled, reg)
    assert data["goals"]["kcal"] == 2000 and data["timezone"] == "Asia/Kolkata"
    assert data["habits"][0]["name"] == "water"
    assert {e["module"] for e in data["entries"]} >= {"meals", "workouts", "expenses", "tasks", "body", "sleep", "habits"}
    json.dumps(data)  # must be serialisable as is


def test_an_empty_database_exports_only_the_header(db, reg):
    assert export_csv(db).strip() == ",".join(CSV_COLUMNS)
    assert export_json(db, reg)["entries"] == []
