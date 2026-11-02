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


def test_complete_by_id(reg, db):
    reg.call("tasks__add_task", {"title": "Buy milk"}, db)
    task_id = db.query("tasks", "task")[0]["id"]
    out = reg.call("tasks__complete_by_id", {"task_id": task_id}, db)
    assert out["completed"]["title"] == "Buy milk"
    assert reg.call("tasks__list_tasks", {}, db)["open_tasks"] == []


def test_complete_by_id_unknown_id_errors(reg, db):
    assert "error" in reg.call("tasks__complete_by_id", {"task_id": 99999}, db)


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


# ---- due dates (P9) ------------------------------------------------------------------------

from datetime import datetime  # noqa: E402
from zoneinfo import ZoneInfo  # noqa: E402

from app.core import clock  # noqa: E402

NOW = datetime(2026, 11, 4, 20, tzinfo=ZoneInfo("Asia/Kolkata")).timestamp()  # Wednesday


@pytest.fixture
def fixed_today(monkeypatch):
    monkeypatch.setattr(clock, "now", lambda: NOW)


def test_due_dates_get_a_status_and_the_list_is_sorted_soonest_first(reg, db, fixed_today):
    reg.call("tasks__add_task", {"title": "Someday"}, db)
    reg.call("tasks__add_task", {"title": "Later", "due": "2026-11-10"}, db)
    reg.call("tasks__add_task", {"title": "Today", "due": "2026-11-04"}, db)
    reg.call("tasks__add_task", {"title": "Late", "due": "2026-11-01"}, db)
    out = reg.call("tasks__list_tasks", {}, db)["open_tasks"]
    assert [(t["title"], t["due_status"]) for t in out] == [
        ("Late", "overdue"), ("Today", "today"), ("Later", "upcoming"), ("Someday", None)]


@pytest.mark.parametrize("due", ["friday", "2026-13-40", "04/11/2026"])
def test_a_due_date_must_be_a_real_calendar_date(reg, db, due):
    assert "error" in reg.call("tasks__add_task", {"title": "x", "due": due}, db)
    assert reg.call("tasks__list_tasks", {}, db)["open_tasks"] == []


def test_set_task_due_changes_and_clears_the_date(reg, db, fixed_today):
    task_id = reg.call("tasks__add_task", {"title": "Renew gym", "due": "2026-11-10"}, db)["logged"]["id"]
    moved = reg.call("tasks__set_task_due", {"task_id": task_id, "due": "2026-11-06"}, db)
    assert moved["updated"]["due"] == "2026-11-06" and moved["updated"]["due_status"] == "upcoming"
    assert reg.call("tasks__list_tasks", {}, db)["open_tasks"][0]["due"] == "2026-11-06"
    reg.call("tasks__set_task_due", {"task_id": task_id, "due": ""}, db)
    assert reg.call("tasks__list_tasks", {}, db)["open_tasks"][0]["due"] is None
    assert "error" in reg.call("tasks__set_task_due", {"task_id": 999, "due": "2026-11-06"}, db)


def test_old_free_text_due_dates_are_kept_but_have_no_status(reg, db, fixed_today):
    db.append("tasks", "task", {"title": "Old task", "due": "next week"})
    task = reg.call("tasks__list_tasks", {}, db)["open_tasks"][0]
    assert task["due"] == "next week" and task["due_status"] is None


def test_retrying_add_task_with_the_same_client_id_makes_one_task(reg, db):
    first = reg.call("tasks__add_task", {"title": "Call mum", "client_id": "t1"}, db)
    again = reg.call("tasks__add_task", {"title": "Call mum", "client_id": "t1"}, db)
    assert again["duplicate"] is True and again["logged"]["id"] == first["logged"]["id"]
    assert len(reg.call("tasks__list_tasks", {}, db)["open_tasks"]) == 1
