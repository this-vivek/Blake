import pytest

from app.core.db import Db
from app.core.registry import Registry


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_suggest_routine_single_group(reg, db):
    out = reg.call("workouts__suggest_routine", {"location": "gym", "muscle_group": "chest"}, db)
    assert out["groups"][0]["muscle_group"] == "chest"
    assert len(out["groups"][0]["exercises"]) > 0
    assert out["unknown_groups"] == []


def test_suggest_routine_multi_group(reg, db):
    out = reg.call(
        "workouts__suggest_routine", {"location": "home", "muscle_group": "chest,biceps"}, db
    )
    assert {g["muscle_group"] for g in out["groups"]} == {"chest", "biceps"}


def test_suggest_routine_unknown_group_reported_not_crashed(reg, db):
    out = reg.call("workouts__suggest_routine", {"location": "gym", "muscle_group": "wings"}, db)
    assert out["groups"] == [] and out["unknown_groups"] == ["wings"]


def test_suggest_routine_requires_valid_location(reg, db):
    assert "error" in reg.call(
        "workouts__suggest_routine", {"location": "space", "muscle_group": "chest"}, db
    )


def test_suggest_meal_returns_real_macros(reg, db):
    out = reg.call("meals__suggest_meal", {"meal": "lunch"}, db)
    assert out["meal"] == "lunch"
    assert out["total"]["kcal"] > 0
    assert all(item["name"] for item in out["items"])


def test_suggest_meal_avoids_recently_eaten(reg, db):
    first = reg.call("meals__suggest_meal", {"meal": "breakfast"}, db)
    eaten_items = [{"name": i["name"], "grams": 100} for i in first["items"]]
    reg.call("meals__log_meal", {"meal": "breakfast", "items": eaten_items}, db)
    second = reg.call("meals__suggest_meal", {"meal": "breakfast"}, db)
    first_names = {i["name"] for i in first["items"]}
    second_names = {i["name"] for i in second["items"]}
    assert not (first_names & second_names)


def test_suggest_meal_rejects_unknown_meal_type(reg, db):
    assert "error" in reg.call("meals__suggest_meal", {"meal": "brunch"}, db)
