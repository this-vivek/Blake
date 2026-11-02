import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.today import build_today
from modules.workouts import DEFAULT_BODYWEIGHT_KG, WARMUP_MET, log_warmup


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_warmup_kcal_uses_the_met_formula_and_default_bodyweight(db):
    out = log_warmup({"seconds": 360}, db)  # 6 minutes
    expected = round(WARMUP_MET * DEFAULT_BODYWEIGHT_KG * 3.5 / 200 * 6, 1)
    assert out["kcal_burned"] == expected and expected > 0
    assert db.get(out["event_id"])["payload"] == {"seconds": 360.0, "kcal_burned": expected}


def test_warmup_uses_bodyweight_from_goals(reg, db):
    reg.call("goals__set_goal", {"bodyweight_kg": 100}, db)
    light = log_warmup({"seconds": 60}, Db(":memory:"))["kcal_burned"]
    heavy = log_warmup({"seconds": 60}, db)["kcal_burned"]
    assert heavy > light


def test_warmup_counts_toward_kcal_burned_today(reg, db):
    reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    before = build_today(db, reg)["training"]["kcal_burned_today"]
    added = log_warmup({"seconds": 300}, db)["kcal_burned"]
    assert build_today(db, reg)["training"]["kcal_burned_today"] == round(before + added, 1)


@pytest.mark.parametrize("seconds", [0, -5, 99999, "abc", None])
def test_warmup_rejects_bad_durations(db, seconds):
    assert "error" in log_warmup({"seconds": seconds}, db)
    assert db.query("workouts", "warmup") == []


def test_warmup_retry_with_same_client_id_is_not_double_counted(db):
    first = log_warmup({"seconds": 60, "client_id": "w1"}, db)
    second = log_warmup({"seconds": 60, "client_id": "w1"}, db)
    assert second["duplicate"] is True and second["event_id"] == first["event_id"]
    assert len(db.query("workouts", "warmup")) == 1
