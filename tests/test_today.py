import datetime
import zoneinfo

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.today import build_today

IST = zoneinfo.ZoneInfo("Asia/Kolkata")


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_today_snapshot_with_fixed_clock(reg, db):
    now = datetime.datetime(2024, 3, 15, 20, 0, tzinfo=IST).timestamp()

    reg.call("goals__set_goal", {"kcal": 2200, "protein_g": 140, "daily_spend": 800}, db)
    reg.call("meals__log_meal", {"meal": "breakfast", "items": [{"name": "egg", "grams": 100}]}, db)
    reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    reg.call("expenses__log_expense", {"amount": 500, "category": "food"}, db)
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)

    out = build_today(db, reg, now=now)

    assert out["nutrition"]["kcal"]["actual"] == 143.0
    assert out["nutrition"]["kcal"]["goal"] == 2200.0
    assert out["nutrition"]["meal_slots_logged"] == ["breakfast"]
    assert out["training"]["sets_today"][0]["exercise"] == "bench press"
    assert out["training"]["muscle_groups_today"] == ["chest"]
    assert out["training"]["prs_today"] == []  # first-ever lift isn't a PR
    assert out["training"]["kcal_burned_today"] == 4.6
    assert out["spend"]["actual"] == 500.0
    assert out["spend"]["goal"] == 800.0
    assert [t["title"] for t in out["open_tasks"]] == ["Buy milk"]
    assert any("protein" in n for n in out["nudges"])


def test_today_nutrition_includes_usual_per_slot(reg, db):
    reg.call("meals__log_meal", {"meal": "breakfast", "items": [{"name": "egg", "grams": 100}]}, db)
    reg.call("meals__log_meal", {"meal": "breakfast", "items": [{"name": "egg", "grams": 100}]}, db)

    out = build_today(db, reg)
    usual = out["nutrition"]["usual"]
    assert set(usual) == {"breakfast", "lunch", "dinner", "snack"}
    assert usual["breakfast"]["times_logged"] == 2
    assert usual["lunch"]["times_logged"] == 0


def test_today_excludes_previous_calendar_day(reg, db):
    yesterday_9pm = datetime.datetime(2024, 3, 14, 21, 0, tzinfo=IST).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=IST).timestamp()

    db.append("meals", "meal", {
        "meal": "dinner", "items": [],
        "total": {"kcal": 500.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
    }, ts=yesterday_9pm)

    out = build_today(db, reg, now=today_0032)
    assert out["nutrition"]["kcal"]["actual"] == 0.0
    assert out["nutrition"]["meal_slots_logged"] == []


def test_today_pr_detection_matches_log_set(reg, db):
    reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 60, "reps": 8}, db)
    second = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 65, "reps": 8}, db)
    assert second["is_pr"]
    out = build_today(db, reg)
    assert out["training"]["prs_today"] == [{"exercise": "bench", "e1rm": second["e1rm"]}]


def test_today_nudge_when_over_spend_budget(reg, db):
    reg.call("goals__set_goal", {"daily_spend": 100}, db)
    reg.call("expenses__log_expense", {"amount": 150, "category": "food"}, db)
    out = build_today(db, reg)
    assert any("budget" in n for n in out["nudges"])


def test_today_caps_nudges_at_two(reg, db):
    reg.call("goals__set_goal", {"protein_g": 200, "daily_spend": 10}, db)
    reg.call("expenses__log_expense", {"amount": 500, "category": "food"}, db)
    out = build_today(db, reg)
    assert len(out["nudges"]) <= 2


def test_today_no_goals_set_has_no_nudge_crash(reg, db):
    out = build_today(db, reg)
    assert isinstance(out["nudges"], list)


def test_today_protein_nudge_only_shows_in_evening(reg, db):
    reg.call("goals__set_goal", {"protein_g": 150}, db)
    reg.call("meals__log_meal", {"meal": "breakfast", "items": [{"name": "egg", "grams": 100}]}, db)

    morning = datetime.datetime(2024, 3, 15, 9, 0, tzinfo=IST).timestamp()
    evening = datetime.datetime(2024, 3, 15, 19, 0, tzinfo=IST).timestamp()

    out_morning = build_today(db, reg, now=morning)
    assert not any("protein" in n for n in out_morning["nudges"])

    out_evening = build_today(db, reg, now=evening)
    assert any("protein" in n and "try" in n for n in out_evening["nudges"])


def test_today_nudge_when_slot_unlogged_past_usual_time(reg, db):
    usual_breakfast = (datetime.datetime.now(IST) - datetime.timedelta(days=2)).replace(
        hour=8, minute=0, second=0, microsecond=0
    )
    db.append("meals", "meal", {
        "meal": "breakfast",
        "items": [{"name": "egg", "grams": 100, "kcal": 143.0, "protein": 12.6, "carbs": 0.7, "fat": 9.5}],
        "total": {"kcal": 143.0, "protein": 12.6, "carbs": 0.7, "fat": 9.5},
    }, ts=usual_breakfast.timestamp())

    before_usual = datetime.datetime.now(IST).replace(hour=7, minute=0, second=0, microsecond=0)
    after_usual = datetime.datetime.now(IST).replace(hour=10, minute=0, second=0, microsecond=0)

    out_before = build_today(db, reg, now=before_usual.timestamp())
    assert not any("breakfast" in n for n in out_before["nudges"])

    out_after = build_today(db, reg, now=after_usual.timestamp())
    assert any("breakfast" in n and "unlogged" in n for n in out_after["nudges"])


def test_kcal_burned_today_survives_partial_session(reg, db):
    # Simulates the app being closed mid-session: only 2 of a planned 4 sets got logged.
    # Each set is logged immediately (not batched at "finish session"), so the total already
    # reflects real progress without any special resume/recovery logic.
    reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    out = build_today(db, reg)
    assert out["training"]["kcal_burned_today"] == 9.2  # 2 x 4.6
