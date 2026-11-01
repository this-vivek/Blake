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
    assert reg.call("meals__daily_summary", {}, db)["last_24h"]["kcal"] == 143.0


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
