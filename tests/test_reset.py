import time

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.reset import reset_day, reset_logs
from app.today import build_today
from modules.meals import add_custom_food
from modules.workouts import set_program, today_program_day


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def _log_everything(reg, db):
    reg.call("meals__log_meal", {"meal": "lunch", "items": [{"name": "rice", "grams": 200}]}, db)
    reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    reg.call("expenses__log_expense", {"amount": 100, "category": "food"}, db)


def test_reset_day_voids_today_but_not_older_entries(reg, db):
    yesterday = time.time() - 2 * 86400
    db.append("expenses", "expense", {"amount": 50.0, "category": "food", "note": ""}, ts=yesterday)
    _log_everything(reg, db)

    out = reset_day(db)

    assert out["voided"] == 3
    assert [e["payload"]["amount"] for e in db.query_active("expenses", "expense")] == [50.0]
    assert db.query_active("meals", "meal") == []
    assert db.query_active("workouts", "set") == []


def test_reset_day_zeroes_the_today_summary(reg, db):
    _log_everything(reg, db)
    reset_day(db)
    today = build_today(db, reg)
    assert today["nutrition"]["kcal"]["actual"] == 0
    assert today["spend"]["actual"] == 0
    assert today["training"]["sets_today"] == []


def test_reset_logs_clears_history_but_keeps_goals_program_and_foods(reg, db):
    reg.call("goals__set_goal", {"kcal": 2000}, db)
    set_program({"days": [{"type": "train", "muscle_group": "chest"}, {"type": "rest"}]}, db)
    add_custom_food({"name": "my shake", "per100": {"kcal": 100, "protein": 10, "carbs": 5, "fat": 2}}, db)
    db.append("expenses", "expense", {"amount": 50.0, "category": "food", "note": ""},
              ts=time.time() - 5 * 86400)
    _log_everything(reg, db)

    out = reset_logs(db)

    assert out["voided"] == 4
    assert db.query_active("expenses", "expense") == []
    assert db.query_active("goals", "goal") != []
    assert db.query_active("workouts", "program") != []
    assert db.query_active("meals", "custom_food") != []
    assert today_program_day(db)["day"]["muscle_group"] == "chest"


def test_reset_day_also_voids_warmups(reg, db):
    from modules.workouts import log_warmup
    log_warmup({"seconds": 300}, db)
    assert reset_day(db)["voided"] == 1
    assert build_today(db, reg)["training"]["kcal_burned_today"] == 0
