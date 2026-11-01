import pytest

from app.core.db import Db
from app.core.registry import Registry


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_add_task_requires_title(reg, db):
    assert "error" in reg.call("tasks__add_task", {}, db)


def test_add_and_list_task(reg, db):
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)
    out = reg.call("tasks__list_tasks", {}, db)
    assert [t["title"] for t in out["open_tasks"]] == ["Buy milk"]


def test_complete_task_removes_from_open_list(reg, db):
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)
    reg.call("tasks__add_task", {"title": "Call dentist"}, db)
    reg.call("tasks__complete_task", {"title": "buy milk"}, db)
    out = reg.call("tasks__list_tasks", {}, db)
    assert [t["title"] for t in out["open_tasks"]] == ["Call dentist"]


def test_complete_task_matches_partial_title(reg, db):
    reg.call("tasks__add_task", {"title": "Call the dentist tomorrow"}, db)
    out = reg.call("tasks__complete_task", {"title": "dentist"}, db)
    assert out["completed"]["title"] == "Call the dentist tomorrow"


def test_complete_task_no_match_errors(reg, db):
    out = reg.call("tasks__complete_task", {"title": "nonexistent"}, db)
    assert "error" in out


def test_voided_task_excluded_from_open_list(reg, db):
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)
    task_id = db.query("tasks", "task")[0]["id"]
    reg.call("events__void_event", {"event_id": task_id}, db)
    assert reg.call("tasks__list_tasks", {}, db)["open_tasks"] == []


def test_voiding_completion_reopens_task(reg, db):
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)
    reg.call("tasks__complete_task", {"title": "buy milk"}, db)
    done_id = db.query("tasks", "task_done")[0]["id"]
    reg.call("events__void_event", {"event_id": done_id}, db)
    out = reg.call("tasks__list_tasks", {}, db)
    assert [t["title"] for t in out["open_tasks"]] == ["Buy milk"]
