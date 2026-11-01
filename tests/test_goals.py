import pytest

from app.core.db import Db
from app.core.registry import Registry


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_get_goals_empty_by_default(reg, db):
    assert reg.call("goals__get_goals", {}, db) == {}


def test_set_goal_single_field(reg, db):
    out = reg.call("goals__set_goal", {"protein_g": 140}, db)
    assert out["updated"] == {"protein_g": 140.0}
    assert reg.call("goals__get_goals", {}, db) == {"protein_g": 140.0}


def test_set_goal_partial_update_preserves_other_fields(reg, db):
    reg.call("goals__set_goal", {"kcal": 2200, "protein_g": 140}, db)
    reg.call("goals__set_goal", {"protein_g": 150}, db)
    goals = reg.call("goals__get_goals", {}, db)
    assert goals == {"kcal": 2200.0, "protein_g": 150.0}


def test_set_goal_requires_at_least_one_field(reg, db):
    assert "error" in reg.call("goals__set_goal", {}, db)


def test_set_goal_rejects_invalid_workout_days(reg, db):
    assert "error" in reg.call("goals__set_goal", {"workout_days_per_week": 9}, db)


def test_set_goal_rejects_negative_kcal(reg, db):
    assert "error" in reg.call("goals__set_goal", {"kcal": -100}, db)


def test_set_goal_bodyweight(reg, db):
    out = reg.call("goals__set_goal", {"bodyweight_kg": 72.5}, db)
    assert out["updated"] == {"bodyweight_kg": 72.5}


def test_set_goal_rejects_unreasonable_bodyweight(reg, db):
    assert "error" in reg.call("goals__set_goal", {"bodyweight_kg": 5}, db)
    assert "error" in reg.call("goals__set_goal", {"bodyweight_kg": 1000}, db)


def test_voided_goal_update_does_not_apply(reg, db):
    reg.call("goals__set_goal", {"protein_g": 140}, db)
    reg.call("goals__set_goal", {"protein_g": 200}, db)
    second_id = db.query("goals", "goal")[0]["id"]  # DESC order: most recent first
    reg.call("events__void_event", {"event_id": second_id}, db)
    assert reg.call("goals__get_goals", {}, db) == {"protein_g": 140.0}
