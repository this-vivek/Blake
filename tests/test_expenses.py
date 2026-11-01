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


def test_daily_summary_totals_and_breaks_down_by_category(reg, db):
    reg.call("expenses__log_expense", {"amount": 100, "category": "food"}, db)
    reg.call("expenses__log_expense", {"amount": 50, "category": "transport"}, db)
    reg.call("expenses__log_expense", {"amount": 25, "category": "food"}, db)
    out = reg.call("expenses__daily_summary", {}, db)
    assert out["last_24h"]["total"] == 175.0
    assert out["last_24h"]["by_category"] == {"food": 125.0, "transport": 50.0}
