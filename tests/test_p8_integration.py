import os
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.core import clock
from app.core.db import Db
from app.core.registry import Registry
from app.insights import insights
from app.reset import reset_day, reset_logs
from app.today import build_today

IST = ZoneInfo("Asia/Kolkata")
NOW = datetime(2026, 11, 4, 20, tzinfo=IST).timestamp()


@pytest.fixture(autouse=True)
def fixed_clock(monkeypatch):
    monkeypatch.setattr(clock, "now", lambda: NOW)


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def seed(reg, db):
    reg.call("habits__log_habit", {"name": "water", "value": 8, "date": "2026-11-02"}, db)
    reg.call("habits__log_habit", {"name": "water", "value": 5}, db)
    reg.call("body__log_weight", {"kg": 72.0, "date": "2026-11-02"}, db)
    reg.call("body__log_weight", {"kg": 71.0}, db)
    reg.call("sleep__log_sleep", {"hours": 7, "quality": 4}, db)


def test_today_carries_habits_weight_and_sleep(reg, db):
    seed(reg, db)
    today = build_today(db, reg, now=NOW)
    water = today["habits"][0]
    assert water["name"] == "water" and water["value"] == 5 and water["target"] == 8 and water["done"] is False
    assert today["weight"]["kg"] == 71.0 and today["weight"]["avg7"] == 71.5
    assert today["sleep"] == {"hours": 7.0, "quality": 4}


def test_today_has_sensible_empty_values_before_anything_is_logged(reg, db):
    today = build_today(db, reg, now=NOW)
    assert today["habits"] == [] and today["sleep"] is None
    assert today["weight"]["kg"] is None and today["weight"]["latest_kg"] is None


def test_the_latest_weight_is_reported_even_when_it_was_not_today(reg, db):
    reg.call("body__log_weight", {"kg": 70.5, "date": "2026-11-01"}, db)
    weight = build_today(db, reg, now=NOW)["weight"]
    assert weight["kg"] is None and weight["latest_kg"] == 70.5 and weight["latest_date"] == "2026-11-01"


def test_insights_has_blocks_for_each_new_module(reg, db):
    seed(reg, db)
    out = insights(db, reg, 14, NOW, "Asia/Kolkata")
    water = out["habits"][0]
    assert water["name"] == "water" and len(water["points"]) == 14
    assert water["hit_days"] == 1 and water["counted_days"] == 2  # 2 Nov done, 3 Nov missed; today not counted
    assert water["hit_pct"] == 50
    assert out["weight"]["latest_kg"] == 71.0 and out["weight"]["change_kg"] == -1.0
    assert out["sleep"]["nights"] == 1 and out["sleep"]["avg_hours"] == 7.0 and out["sleep"]["avg_quality"] == 4.0


def test_insights_blocks_are_empty_not_missing_with_no_data(reg, db):
    out = insights(db, reg, 14, NOW, "Asia/Kolkata")
    assert out["habits"] == [] and out["weight"] == {"points": []} and out["sleep"] == {"points": [], "nights": 0}


def test_reset_day_undoes_todays_entries_of_the_new_modules_only(reg, db):
    seed(reg, db)
    reset_day(db)
    today = build_today(db, reg, now=NOW)
    assert today["habits"][0]["value"] == 0 and today["sleep"] is None and today["weight"]["kg"] is None
    # the backfilled entries from 2 November are not today's, so they stay
    assert reg.call("habits__habit_status", {}, db)["habits"][0]["streak"]["best"] == 1
    assert reg.call("body__weight_trend", {"days": 30}, db)["points"][0]["kg"] == 72.0


def test_reset_all_logs_clears_them_but_keeps_the_habit_definitions(reg, db):
    seed(reg, db)
    reset_logs(db)
    assert reg.call("body__weight_trend", {"days": 30}, db)["points"] == []
    assert reg.call("sleep__sleep_summary", {}, db)["nights_logged"] == 0
    habits = reg.call("habits__habit_status", {}, db)["habits"]
    assert [h["name"] for h in habits] == ["water"] and habits[0]["value"] == 0


def test_the_api_routes_log_through_the_modules(reg, monkeypatch):
    pytest.importorskip("fastapi")
    pytest.importorskip("anthropic")
    pytest.importorskip("httpx")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test")
    monkeypatch.setenv("BLAKE_DB_PATH", ":memory:")
    monkeypatch.delenv("BLAKE_API_KEY", raising=False)
    import importlib

    from fastapi.testclient import TestClient

    import app.main as main
    importlib.reload(main)
    client = TestClient(main.app)

    assert client.post("/log/habit", json={"name": "water", "value": 2}).json()["day_total"] == 2
    assert client.post("/habits", json={"name": "read", "kind": "count", "target": 20, "unit": "page"}).json()["created"]
    assert client.post("/log/weight", json={"kg": 72.4}).json()["logged"]["kg"] == 72.4
    assert client.post("/log/sleep", json={"hours": 7.5, "quality": 4}).json()["logged"]["hours"] == 7.5
    assert "error" in client.post("/log/sleep", json={"hours": 99}).json()
    body = client.get("/insights?days=7").json()
    assert {"habits", "weight", "sleep"} <= set(body)
    assert os.environ["BLAKE_DB_PATH"] == ":memory:"
