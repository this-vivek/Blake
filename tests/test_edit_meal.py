import pytest

from app.core.db import Db
from app.core.registry import Registry
from modules.meals import edit_meal


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def _log(reg, db, grams):
    return reg.call("meals__log_meal", {"meal": "lunch", "items": [{"name": "rice", "grams": grams}]}, db)


def test_edit_replaces_meal_and_keeps_slot(reg, db):
    old = _log(reg, db, 100)
    out = edit_meal({"event_id": old["event_id"], "items": [{"name": "rice", "grams": 200}]}, reg, db)
    assert out["replaced"] == old["event_id"]
    assert out["logged"][0]["grams"] == 200
    assert db.get(out["event_id"])["payload"]["meal"] == "lunch"
    active = db.query_active("meals", "meal")
    assert [e["id"] for e in active] == [out["event_id"]]


def test_edit_unmatched_keeps_original(reg, db):
    old = _log(reg, db, 100)
    out = edit_meal({"event_id": old["event_id"], "items": [{"name": "zzzxyz", "grams": 50}]}, reg, db)
    assert "error" in out
    assert [e["id"] for e in db.query_active("meals", "meal")] == [old["event_id"]]


def test_edit_rejects_non_meal_event(reg, db):
    assert "error" in edit_meal({"event_id": 999, "items": [{"name": "rice", "grams": 1}]}, reg, db)
