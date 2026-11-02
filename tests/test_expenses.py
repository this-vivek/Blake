import pytest

from app.core.db import Db
from app.core.registry import Registry


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_log_expense(reg, db):
    out = reg.call("expenses__log_expense", {"amount": 250, "category": "food"}, db)
    assert out["logged"] == {"amount": 250.0, "category": "food", "note": ""}


def test_log_expense_rejects_missing_fields(reg, db):
    assert "error" in reg.call("expenses__log_expense", {"amount": 10}, db)


def test_log_expense_rejects_non_positive_amount(reg, db):
    assert "error" in reg.call("expenses__log_expense", {"amount": 0, "category": "food"}, db)


def test_log_expense_unknown_category_falls_back_to_other(reg, db):
    out = reg.call("expenses__log_expense", {"amount": 5, "category": "bogus"}, db)
    assert out["logged"]["category"] == "other"


def test_log_expense_retry_with_same_client_id_does_not_double_log(reg, db):
    args = {"amount": 250, "category": "food", "client_id": "expense-retry-1"}
    first = reg.call("expenses__log_expense", args, db)
    second = reg.call("expenses__log_expense", args, db)
    assert first["event_id"] == second["event_id"]
    assert second.get("duplicate") is True
    assert len(db.query("expenses", "expense")) == 1


def test_daily_summary_totals_and_breaks_down_by_category(reg, db):
    reg.call("expenses__log_expense", {"amount": 100, "category": "food"}, db)
    reg.call("expenses__log_expense", {"amount": 50, "category": "transport"}, db)
    reg.call("expenses__log_expense", {"amount": 25, "category": "food"}, db)
    out = reg.call("expenses__daily_summary", {}, db)
    assert out["today"]["total"] == 175.0
    assert out["today"]["by_category"] == {"food": 125.0, "transport": 50.0}


def test_voided_expense_excluded_from_daily_summary(reg, db):
    reg.call("expenses__log_expense", {"amount": 500, "category": "food"}, db)
    expense_id = db.query("expenses", "expense")[0]["id"]
    reg.call("events__void_event", {"event_id": expense_id}, db)
    assert reg.call("expenses__daily_summary", {}, db)["today"]["total"] == 0.0


def test_daily_summary_excludes_spend_from_previous_calendar_day(reg, db, monkeypatch):
    import datetime
    import zoneinfo

    from app.core import clock

    ist = zoneinfo.ZoneInfo("Asia/Kolkata")
    yesterday_9pm = datetime.datetime(2024, 3, 14, 21, 0, tzinfo=ist).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=ist).timestamp()

    db.append("expenses", "expense", {"amount": 500.0, "category": "food", "note": ""},
               ts=yesterday_9pm)

    monkeypatch.setattr(clock, "now", lambda: today_0032)
    out = reg.call("expenses__daily_summary", {}, db)
    assert out["today"]["total"] == 0.0
