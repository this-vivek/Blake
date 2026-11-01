import pytest

from app.core.db import Db
from app.core.registry import Registry


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_void_event_marks_target_and_returns_what_was_voided(reg, db):
    event_id = db.append("expenses", "expense", {"amount": 100.0, "category": "food", "note": ""})
    out = reg.call("events__void_event", {"event_id": event_id}, db)
    assert out["voided"] == event_id
    assert out["was"]["payload"]["amount"] == 100.0


def test_void_unknown_event_errors(reg, db):
    assert "error" in reg.call("events__void_event", {"event_id": 99999}, db)


def test_cannot_void_a_void_event(reg, db):
    event_id = db.append("expenses", "expense", {"amount": 1.0, "category": "food", "note": ""})
    void_result = reg.call("events__void_event", {"event_id": event_id}, db)
    void_event_id = db.query("expenses", "void")[0]["id"]
    assert void_result["voided"] == event_id
    assert "error" in reg.call("events__void_event", {"event_id": void_event_id}, db)


def test_query_active_excludes_voided_events(db):
    a = db.append("expenses", "expense", {"amount": 10.0}, ts=1000.0)
    db.append("expenses", "expense", {"amount": 20.0}, ts=2000.0)
    db.append("expenses", "void", {"target_id": a})
    active = db.query_active("expenses", "expense")
    assert [e["payload"]["amount"] for e in active] == [20.0]


def test_get_returns_event_by_id_regardless_of_module(db):
    event_id = db.append("tasks", "task", {"title": "x"})
    fetched = db.get(event_id)
    assert fetched["module"] == "tasks" and fetched["payload"]["title"] == "x"


def test_get_returns_none_for_unknown_id(db):
    assert db.get(123456) is None


def test_append_with_client_id_is_idempotent(db):
    first_id = db.append("expenses", "expense", {"amount": 10.0}, client_id="abc-123")
    second_id = db.append("expenses", "expense", {"amount": 10.0}, client_id="abc-123")
    assert first_id == second_id
    assert len(db.query("expenses", "expense")) == 1


def test_append_without_client_id_is_not_deduplicated(db):
    db.append("expenses", "expense", {"amount": 10.0})
    db.append("expenses", "expense", {"amount": 10.0})
    assert len(db.query("expenses", "expense")) == 2


def test_find_by_client_id(db):
    event_id = db.append("expenses", "expense", {"amount": 5.0}, client_id="xyz")
    found = db.find_by_client_id("xyz")
    assert found["id"] == event_id
    assert db.find_by_client_id("missing") is None
