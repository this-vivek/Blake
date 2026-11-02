import pytest

from app.core.db import Db
from app.core.registry import Registry
from modules.meals import usual_combo


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


def test_usual_combo_empty_when_never_logged(db):
    out = usual_combo("breakfast", db)
    assert out == {"meal": "breakfast", "items": [], "total": {"kcal": 0.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0}, "times_logged": 0}


def test_usual_combo_picks_most_frequent(reg, db):
    reg.call("meals__log_meal", {"meal": "lunch", "items": [{"name": "rice", "grams": 150}, {"name": "dal", "grams": 150}]}, db)
    reg.call("meals__log_meal", {"meal": "lunch", "items": [{"name": "rice", "grams": 150}, {"name": "dal", "grams": 150}]}, db)
    reg.call("meals__log_meal", {"meal": "lunch", "items": [{"name": "chole", "grams": 200}]}, db)

    out = usual_combo("lunch", db)
    assert out["times_logged"] == 2
    assert {i["name"] for i in out["items"]} == {"rice", "dal"}


def test_usual_combo_uses_most_recent_occurrences_grams(reg, db):
    reg.call("meals__log_meal", {"meal": "snack", "items": [{"name": "banana", "grams": 100}]}, db)
    reg.call("meals__log_meal", {"meal": "snack", "items": [{"name": "banana", "grams": 150}]}, db)

    out = usual_combo("snack", db)
    assert out["times_logged"] == 2
    assert out["items"][0]["grams"] == 150  # most recent occurrence, not an average


def test_usual_combo_only_considers_matching_slot(reg, db):
    reg.call("meals__log_meal", {"meal": "breakfast", "items": [{"name": "egg", "grams": 100}]}, db)
    out = usual_combo("lunch", db)
    assert out["times_logged"] == 0


def test_usual_combo_ignores_meals_outside_30_days(reg, db):
    import time
    old_ts = time.time() - 40 * 24 * 3600
    db.append("meals", "meal", {
        "meal": "dinner", "items": [{"name": "roti", "grams": 80, "kcal": 1, "protein": 1, "carbs": 1, "fat": 1}],
        "total": {"kcal": 1.0, "protein": 1.0, "carbs": 1.0, "fat": 1.0},
    }, ts=old_ts)
    out = usual_combo("dinner", db)
    assert out["times_logged"] == 0
