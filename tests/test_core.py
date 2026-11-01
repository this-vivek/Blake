from types import SimpleNamespace as NS

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.core.router import run_turn


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_db_roundtrip(db):
    db.append("x", "k", {"a": 1})
    assert db.query("x", "k")[0]["payload"] == {"a": 1}


def test_registry_discovers_and_namespaces(reg):
    names = {t["name"] for t in reg.tool_specs()}
    assert {"meals__log_meal", "workouts__log_set"} <= names


def test_unknown_tool_is_error_not_crash(reg, db):
    assert "error" in reg.call("nope__x", {}, db)


def test_meal_macros_from_lookup_not_model(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "egg", "grams": 100}]}, db)
    assert out["total"]["kcal"] == 143.0
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 143.0


def test_log_meal_retry_with_same_client_id_does_not_double_log(reg, db):
    args = {"items": [{"name": "egg", "grams": 100}], "client_id": "retry-1"}
    first = reg.call("meals__log_meal", args, db)
    second = reg.call("meals__log_meal", args, db)
    assert first["total"]["kcal"] == 143.0
    assert second["total"]["kcal"] == 143.0
    assert second.get("duplicate") is True
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 143.0  # not 286.0


def test_voided_meal_excluded_from_daily_summary(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "egg", "grams": 100}]}, db)
    meal_id = db.query("meals", "meal")[0]["id"]
    reg.call("events__void_event", {"event_id": meal_id}, db)
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 0.0


def test_daily_summary_uses_local_calendar_day_not_rolling_24h(reg, db, monkeypatch):
    import datetime
    import zoneinfo

    from app.core import clock

    ist = zoneinfo.ZoneInfo("Asia/Kolkata")
    # A dinner logged at 21:00 IST yesterday is only ~3.5h before "now" (00:32 IST today) —
    # well within a rolling 24h window, but it belongs to yesterday's calendar day.
    yesterday_9pm = datetime.datetime(2024, 3, 14, 21, 0, tzinfo=ist).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=ist).timestamp()

    db.append("meals", "meal", {
        "meal": "dinner", "items": [],
        "total": {"kcal": 500.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
    }, ts=yesterday_9pm)

    monkeypatch.setattr(clock, "now", lambda: today_0032)
    out = reg.call("meals__daily_summary", {}, db)
    assert out["today"]["kcal"] == 0.0


def test_daily_summary_includes_meal_logged_just_after_local_midnight(reg, db, monkeypatch):
    import datetime
    import zoneinfo

    from app.core import clock

    ist = zoneinfo.ZoneInfo("Asia/Kolkata")
    today_0005 = datetime.datetime(2024, 3, 15, 0, 5, tzinfo=ist).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=ist).timestamp()

    db.append("meals", "meal", {
        "meal": "snack", "items": [],
        "total": {"kcal": 120.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
    }, ts=today_0005)

    monkeypatch.setattr(clock, "now", lambda: today_0032)
    out = reg.call("meals__daily_summary", {}, db)
    assert out["today"]["kcal"] == 120.0


def test_unknown_food_reported(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 50}]}, db)
    assert out["unknown_items"] == ["dragonfruit"] and out["logged"] == []


def test_unknown_food_queued_for_curation(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 50}]}, db)
    queued = db.query("meals", "unmatched")
    assert queued[0]["payload"] == {"name": "dragonfruit", "grams": 50.0}


def test_meal_rejects_bad_input(reg, db):
    assert "error" in reg.call("meals__log_meal", {"items": []}, db)


def test_parenthetical_ingredients_dont_cause_false_match(reg, db):
    # "onion" appears inside the parenthetical description but the dish itself is poha,
    # not onion — matching must not pick an ingredient mentioned in the aside.
    out = reg.call("meals__log_meal", {"items": [
        {"name": "poha (cooked flattened rice with turmeric, onion, peanuts)", "grams": 200}
    ]}, db)
    assert out["logged"][0]["name"] == "poha"
    assert out["unknown_items"] == []


def test_pr_detection(reg, db):
    a = reg.call("workouts__log_set", {"exercise": "Bench", "weight_kg": 60, "reps": 8}, db)
    b = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 65, "reps": 8}, db)
    c = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 50, "reps": 8}, db)
    assert a["first_time"] and not a["is_pr"]
    assert b["is_pr"] and not c["is_pr"]


def test_voided_set_excluded_from_pr_detection(reg, db):
    reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 65, "reps": 8}, db)
    pr_set_id = db.query("workouts", "set")[0]["id"]
    reg.call("events__void_event", {"event_id": pr_set_id}, db)
    # the 65kg PR is voided, so logging 60kg again should look like a fresh first-time lift
    out = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 60, "reps": 8}, db)
    assert out["first_time"] and not out["is_pr"]


def test_log_set_retry_with_same_client_id_does_not_double_log(reg, db):
    args = {"exercise": "squat", "weight_kg": 80, "reps": 5, "client_id": "set-retry-1"}
    first = reg.call("workouts__log_set", args, db)
    second = reg.call("workouts__log_set", args, db)
    assert first["e1rm"] == second["e1rm"]
    assert second.get("duplicate") is True
    assert len(db.query("workouts", "set")) == 1


class FakeClient:
    """Scripted: first call -> tool_use, second -> final text."""
    def __init__(self):
        self.n = 0
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        self.n += 1
        if self.n == 1:
            return NS(content=[NS(type="tool_use", id="t1", name="workouts__log_set",
                                  input={"exercise": "squat", "weight_kg": 100, "reps": 5})])
        return NS(content=[NS(type="text", text="Logged.")])


def test_router_loop_executes_tools(reg, db):
    res = run_turn(FakeClient(), db, reg, "squat 100x5")
    assert res["text"] == "Logged."
    assert res["calls"][0]["name"] == "workouts__log_set"
    assert db.query("workouts", "set")


def test_run_turn_never_returns_raw_message_content(reg, db):
    # app/main.py persists {"text": ..., "has_image": ..., **run_turn(...)} as the "chat"/
    # "turn" event — run_turn's return value must never carry the raw content blocks (which
    # is where a base64-encoded photo would live), or a photo log would blow through Neon's
    # free storage tier. Only "text" and "calls" (tool name/input/output) may appear.
    fake_image_data = "a" * 2000  # stand-in for what a base64 JPEG looks like
    content = [
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                      "data": fake_image_data}},
        {"type": "text", "text": "ate 2 eggs"},
    ]
    res = run_turn(FakeClient(), db, reg, content)
    assert set(res.keys()) <= {"text", "calls"}
    assert fake_image_data not in str(res)
