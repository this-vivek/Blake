from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.core import clock
from app.core.db import Db
from app.core.registry import Registry

IST = ZoneInfo("Asia/Kolkata")
# Wednesday 4 November 2026, 20:00 IST
NOW = datetime(2026, 11, 4, 20, tzinfo=IST).timestamp()


@pytest.fixture(autouse=True)
def fixed_clock(monkeypatch):
    monkeypatch.setattr(clock, "now", lambda: NOW)


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def call(reg, db, tool, **args):
    return reg.call(tool, args, db)


# ---- habits -----------------------------------------------------------------------------------

def test_water_exists_without_setup_and_counts_toward_eight_glasses(reg, db):
    first = call(reg, db, "habits__log_habit", name="water", value=3)
    assert first["day_total"] == 3 and first["done"] is False and first["target"] == 8
    done = call(reg, db, "habits__log_habit", name="water", value=5)
    assert done["day_total"] == 8 and done["done"] is True


def test_an_unknown_habit_asks_to_create_it_first_and_lists_the_known_ones(reg, db):
    call(reg, db, "habits__create_habit", name="meditation")
    out = call(reg, db, "habits__log_habit", name="yoga")
    assert "Create it first" in out["error"] and out["known_habits"] == ["meditation"]


def test_yes_no_habit_is_done_after_one_log(reg, db):
    call(reg, db, "habits__create_habit", name="  Meditation ")
    out = call(reg, db, "habits__log_habit", name="meditation")
    assert out["done"] is True and out["logged"]["value"] == 1


def test_creating_the_same_habit_twice_changes_nothing_and_a_new_target_updates_it(reg, db):
    spec = dict(name="reading", kind="count", target=20, unit="page")
    assert call(reg, db, "habits__create_habit", **spec)["created"] is True
    assert call(reg, db, "habits__create_habit", **spec)["created"] is False
    updated = call(reg, db, "habits__create_habit", **{**spec, "target": 30})
    assert updated["updated"] is True
    assert call(reg, db, "habits__habit_status")["habits"][0]["target"] == 30


@pytest.mark.parametrize("args", [
    {"name": ""}, {"name": "x", "kind": "weird"}, {"name": "x", "kind": "count", "target": 0},
    {"name": "x", "kind": "count", "target": "lots"},
])
def test_create_habit_validates(reg, db, args):
    assert "error" in call(reg, db, "habits__create_habit", **args)


@pytest.mark.parametrize("date", ["2026-11-05", "2026-09-01", "yesterday"])
def test_backfill_rejects_future_old_and_malformed_dates(reg, db, date):
    assert "error" in call(reg, db, "habits__log_habit", name="water", value=1, date=date)


def test_backfill_files_the_log_under_that_day(reg, db):
    call(reg, db, "habits__log_habit", name="water", value=2, date="2026-11-02")
    status = call(reg, db, "habits__habit_status")["habits"][0]
    assert status["value"] == 0  # nothing logged today
    assert status["streak"]["best"] == 0  # 2 glasses on the 2nd is below the target of 8


def _log_days(reg, db, name, *days):
    for day in days:
        call(reg, db, "habits__log_habit", name=name, date=day)


def test_one_missed_day_a_week_is_forgiven_and_a_second_breaks_the_streak(reg, db):
    call(reg, db, "habits__create_habit", name="stretch")
    _log_days(reg, db, "stretch", "2026-11-02", "2026-11-04")        # Mon, Wed; Tue missed
    s = call(reg, db, "habits__habit_status")["habits"][0]["streak"]
    assert s["current"] == 2 and s["grace_used_this_week"] is True

    other = Db(":memory:")
    call(reg, other, "habits__create_habit", name="stretch")
    _log_days(reg, other, "stretch", "2026-11-02")                   # Mon; Tue and Wed (today) not done
    s = call(reg, other, "habits__habit_status")["habits"][0]["streak"]
    assert s["current"] == 1  # today is not over; Tuesday is forgiven


def test_two_misses_in_the_same_week_end_the_streak(reg, db, monkeypatch):
    friday = datetime(2026, 11, 6, 20, tzinfo=IST).timestamp()
    monkeypatch.setattr(clock, "now", lambda: friday)
    call(reg, db, "habits__create_habit", name="stretch")
    _log_days(reg, db, "stretch", "2026-11-02", "2026-11-04", "2026-11-06")  # Tue and Thu missed
    s = call(reg, db, "habits__habit_status")["habits"][0]["streak"]
    assert s["current"] == 1 and s["best"] == 2


def test_voiding_a_log_takes_it_back_out_of_the_total(reg, db):
    out = call(reg, db, "habits__log_habit", name="water", value=4)
    call(reg, db, "events__void_event", event_id=out["event_id"])
    assert call(reg, db, "habits__habit_status")["habits"][0]["value"] == 0


def test_archived_habits_disappear_but_cannot_be_logged(reg, db):
    call(reg, db, "habits__create_habit", name="journal")
    call(reg, db, "habits__archive_habit", name="journal")
    assert call(reg, db, "habits__habit_status")["habits"] == []
    assert "error" in call(reg, db, "habits__log_habit", name="journal")


def test_retrying_a_log_with_the_same_client_id_does_not_double_count(reg, db):
    first = call(reg, db, "habits__log_habit", name="water", value=2, client_id="h1")
    again = call(reg, db, "habits__log_habit", name="water", value=2, client_id="h1")
    assert again["duplicate"] is True and again["event_id"] == first["event_id"]
    assert call(reg, db, "habits__habit_status")["habits"][0]["value"] == 2


# ---- body weight ------------------------------------------------------------------------------

@pytest.mark.parametrize("kg", [10, 400, "heavy", None])
def test_weight_must_be_plausible(reg, db, kg):
    assert "error" in call(reg, db, "body__log_weight", kg=kg)


def test_seven_day_average_is_the_mean_of_weigh_ins_in_the_last_seven_days(reg, db):
    for day, kg in (("2026-11-02", 72.0), ("2026-11-03", 71.0), ("2026-11-04", 70.0)):
        call(reg, db, "body__log_weight", kg=kg, date=day)
    out = call(reg, db, "body__weight_trend", days=30)
    assert [p["avg7"] for p in out["points"]] == [72.0, 71.5, 71.0]
    assert out["latest_kg"] == 70.0 and out["avg7"] == 71.0 and out["change_kg"] == -2.0


def test_weigh_ins_older_than_a_week_leave_the_average(reg, db):
    call(reg, db, "body__log_weight", kg=80, date="2026-10-27")  # 8 days before the 4th
    call(reg, db, "body__log_weight", kg=70, date="2026-11-04")
    assert call(reg, db, "body__weight_trend", days=30)["points"][-1]["avg7"] == 70.0


def test_the_latest_weigh_in_of_a_day_wins(reg, db):
    call(reg, db, "body__log_weight", kg=72.0)
    call(reg, db, "body__log_weight", kg=71.6)
    pts = call(reg, db, "body__weight_trend", days=7)["points"]
    assert len(pts) == 1 and pts[0]["kg"] == 71.6


def test_weight_summary_and_empty_trend(reg, db):
    assert call(reg, db, "body__weight_trend", days=7)["points"] == []
    call(reg, db, "body__log_weight", kg=72.4)
    assert call(reg, db, "body__daily_summary")["today"]["kg"] == 72.4
    assert "error" in call(reg, db, "body__weight_trend", days=0)


# ---- sleep -----------------------------------------------------------------------------------

@pytest.mark.parametrize("args", [
    {"hours": 0}, {"hours": 30}, {"hours": "lots"}, {"hours": 7, "quality": 6}, {"hours": 7, "quality": 0},
])
def test_sleep_inputs_are_validated(reg, db, args):
    assert "error" in call(reg, db, "sleep__log_sleep", **args)


def test_quality_is_optional_and_the_summary_averages_only_what_exists(reg, db):
    call(reg, db, "sleep__log_sleep", hours=7, quality=4, date="2026-11-02")
    call(reg, db, "sleep__log_sleep", hours=6, date="2026-11-03")
    call(reg, db, "sleep__log_sleep", hours=8, quality=2, date="2026-11-04")
    out = call(reg, db, "sleep__sleep_summary", days=7)
    assert out["nights_logged"] == 3 and out["avg_hours"] == 7.0 and out["avg_quality"] == 3.0


def test_the_latest_entry_for_a_night_wins(reg, db):
    call(reg, db, "sleep__log_sleep", hours=5)
    call(reg, db, "sleep__log_sleep", hours=6.5, quality=3)
    assert call(reg, db, "sleep__daily_summary")["today"] == {"hours": 6.5, "quality": 3}


def test_the_summary_only_covers_the_window(reg, db):
    call(reg, db, "sleep__log_sleep", hours=9, date="2026-10-20")
    call(reg, db, "sleep__log_sleep", hours=6)
    assert call(reg, db, "sleep__sleep_summary", days=7)["nights_logged"] == 1


def test_no_sleep_logged_gives_an_empty_summary(reg, db):
    out = call(reg, db, "sleep__sleep_summary")
    assert out["nights_logged"] == 0 and "avg_hours" not in out
