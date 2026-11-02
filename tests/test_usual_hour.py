import time
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.today import build_today

IST = ZoneInfo("Asia/Kolkata")


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def meal_at(db, meal, days_ago, hour, minute=0):
    day = datetime.fromtimestamp(time.time() - days_ago * 86400, IST)
    ts = day.replace(hour=hour, minute=minute, second=0, microsecond=0).timestamp()
    db.append("meals", "meal", {"meal": meal, "items": [{"name": "egg", "grams": 50}],
              "total": {"kcal": 70, "protein": 6, "carbs": 0, "fat": 5}}, ts=ts)


def test_today_reports_the_median_log_hour_per_slot_for_reminders(reg, db):
    for days_ago, (h, m) in zip((1, 2, 3), ((8, 0), (8, 30), (9, 0))):
        meal_at(db, "breakfast", days_ago, h, m)
    usual = build_today(db, reg)["nutrition"]["usual"]
    assert usual["breakfast"]["usual_hour"] == pytest.approx(8.5)
    assert usual["dinner"]["usual_hour"] is None  # no history: the app falls back to a default
