import json
import re
from datetime import datetime
from types import SimpleNamespace as NS
from zoneinfo import ZoneInfo

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.reset import reset_logs
from app.weekly_review import (
    MAX_WORDS,
    NUMBER_RE,
    build_packet,
    numbers_outside_packet,
    weekly_review,
    write_fallback,
)

IST = ZoneInfo("Asia/Kolkata")
TZ = "Asia/Kolkata"


def at(day: str, hh: int = 13, mm: int = 0) -> float:
    y, m, d = (int(x) for x in day.split("-"))
    return datetime(y, m, d, hh, mm, tzinfo=IST).timestamp()


# Wednesday 4 Nov. The last completed week is Monday 26 Oct to Sunday 1 Nov.
NOW = at("2026-11-04", 20)


class ScriptedClient:
    """Returns the scripted replies in order and counts the calls."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.calls = 0
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        self.calls += 1
        reply = self.replies.pop(0) if self.replies else self.replies_last
        self.replies_last = reply
        if isinstance(reply, Exception):
            raise reply
        return NS(content=[NS(type="text", text=reply)])


def reply(summary, focus=()):
    return json.dumps({"summary": summary, "focus": list(focus)})


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


@pytest.fixture
def week(reg, db):
    """A week with data for every part of the packet."""
    reg.call("goals__set_goal", {"kcal": 2000, "protein_g": 100, "daily_spend": 500,
                                 "workout_days_per_week": 3}, db)
    for day, kcal, protein in (("2026-10-26", 2000, 110), ("2026-10-27", 1500, 90), ("2026-10-28", 2100, 120)):
        db.append("meals", "meal", {"meal": "lunch", "items": [],
                  "total": {"kcal": kcal, "protein": protein, "carbs": 0, "fat": 0}}, ts=at(day))
    db.append("expenses", "expense", {"amount": 600.0, "category": "food", "note": ""}, ts=at("2026-10-27"))
    for day, e1rm in (("2026-10-26", 76.0), ("2026-10-28", 80.0)):
        db.append("workouts", "set", {"exercise": "bench press", "weight_kg": 60, "reps": 8,
                                      "e1rm": e1rm, "kcal_burned": 5}, ts=at(day))
    return db


def all_numbers_come_from_the_packet(review) -> bool:
    text = " ".join([review["summary"], *review["focus"]])
    return numbers_outside_packet(text, review["packet"]) == []


GOOD = reply(
    "You logged meals on 3 of 7 days and averaged 1867 kcal and 107 g of protein. "
    "You trained on 2 days against a goal of 3.",
    ["Raise protein toward 100 g a day.", "Aim for 3 training days."],
)


# ---- the packet -------------------------------------------------------------------------------

def test_packet_numbers_for_last_completed_week(reg, week):
    from datetime import date
    p = build_packet(week, reg, date(2026, 10, 26), IST)
    assert p["days_logged_meals"] == 3
    assert p["kcal"] == {"avg": 1867, "goal": 2000, "days_on_target": 2}
    assert p["protein_g"] == {"avg": 107, "goal": 100, "days_hit": 2}
    assert p["spend"] == {"total": 600, "daily_budget": 500, "days_over_budget": 1}
    assert p["training"] == {"days": 2, "sets": 2, "goal_days": 3}
    assert p["volume_kg_by_group"] == {"chest": 960}
    assert p["personal_records"] == [{"exercise": "bench press", "e1rm_kg": 80}]
    assert p["personal_record_count"] == 1


def test_sunday_night_belongs_to_the_week_and_monday_morning_does_not(reg, db):
    db.append("expenses", "expense", {"amount": 100.0, "category": "food", "note": ""}, ts=at("2026-11-01", 23, 30))
    db.append("expenses", "expense", {"amount": 900.0, "category": "food", "note": ""}, ts=at("2026-11-02", 0, 10))
    from datetime import date
    assert build_packet(db, reg, date(2026, 10, 26), IST)["spend"]["total"] == 100


# ---- generation and caching ---------------------------------------------------------------------

def test_review_is_written_by_the_model_saved_and_returned_as_saved_afterwards(reg, week):
    client = ScriptedClient(GOOD)
    first = weekly_review(week, reg, client, NOW, TZ)
    second = weekly_review(week, reg, client, NOW + 3600, TZ)
    assert first["source"] == "sonnet" and first["summary"].startswith("You logged meals on 3 of 7")
    assert second == first and client.calls == 1
    assert len(week.query_active("insights", "weekly_review")) == 1


def test_a_new_week_gets_a_new_review(reg, week):
    client = ScriptedClient(GOOD)
    weekly_review(week, reg, client, NOW, TZ)
    next_week = at("2026-11-11", 9)  # the following Wednesday: a new "last completed week"
    out = weekly_review(week, reg, ScriptedClient(GOOD), next_week, TZ)
    assert out.get("empty") is True  # that week has no logs, and the model is not called


def test_empty_week_does_not_call_the_model(reg, db):
    client = ScriptedClient(GOOD)
    out = weekly_review(db, reg, client, NOW, TZ)
    assert out["empty"] is True and client.calls == 0
    assert db.query_active("insights", "weekly_review") == []


# ---- the numbers rule (every number in the write-up must be in the packet) ----------------------

def test_every_number_in_an_accepted_model_review_is_in_the_packet(reg, week):
    out = weekly_review(week, reg, ScriptedClient(GOOD), NOW, TZ)
    assert out["source"] == "sonnet" and all_numbers_come_from_the_packet(out)


def test_a_review_with_an_invented_number_is_retried_then_accepted(reg, week):
    bad = reply("You averaged 2400 kcal.", [])
    client = ScriptedClient(bad, GOOD)
    out = weekly_review(week, reg, client, NOW, TZ)
    assert client.calls == 2 and out["source"] == "sonnet" and all_numbers_come_from_the_packet(out)


def test_two_bad_replies_fall_back_to_code_written_text_with_only_packet_numbers(reg, week):
    bad = reply("You averaged 2400 kcal and slept 8 hours.", ["Eat 3000 kcal."])
    client = ScriptedClient(bad, bad)
    out = weekly_review(week, reg, client, NOW, TZ)
    assert out["source"] == "fallback" and client.calls == 2
    assert all_numbers_come_from_the_packet(out) and out["summary"]


@pytest.mark.parametrize("bad", [
    "not json at all",
    reply(" ".join(["word"] * (MAX_WORDS + 1))),
    json.dumps({"summary": "", "focus": []}),
    json.dumps(["not", "an", "object"]),
])
def test_unusable_replies_fall_back(reg, week, bad):
    out = weekly_review(week, reg, ScriptedClient(bad, bad), NOW, TZ)
    assert out["source"] == "fallback" and all_numbers_come_from_the_packet(out)


def test_an_api_error_falls_back_instead_of_failing(reg, week):
    out = weekly_review(week, reg, ScriptedClient(RuntimeError("down"), RuntimeError("down")), NOW, TZ)
    assert out["source"] == "fallback"


def test_summary_is_capped_at_120_words_and_focus_at_three(reg, week):
    long_ok = reply("Steady week.", ["one", "two", "three", "four", "five"])
    out = weekly_review(week, reg, ScriptedClient(long_ok), NOW, TZ)
    assert len(out["summary"].split()) <= MAX_WORDS and len(out["focus"]) == 3


def test_thousands_separators_and_decimals_are_understood(reg, week):
    from datetime import date
    packet = build_packet(week, reg, date(2026, 10, 26), IST)
    assert numbers_outside_packet("averaged 1,867 kcal and 107.0 g", packet) == []
    assert numbers_outside_packet("averaged 1,999 kcal", packet) == ["1,999"]


def test_fallback_text_alone_never_uses_a_number_outside_the_packet(reg, week):
    from datetime import date
    packet = build_packet(week, reg, date(2026, 10, 26), IST)
    written = write_fallback(packet)
    text = written["summary"] + " " + " ".join(written["focus"])
    assert numbers_outside_packet(text, packet) == []
    assert re.search(NUMBER_RE, text)


def test_resetting_all_logs_removes_saved_reviews(reg, week):
    weekly_review(week, reg, ScriptedClient(GOOD), NOW, TZ)
    reset_logs(week)
    assert week.query_active("insights", "weekly_review") == []
