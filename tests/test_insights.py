from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.insights import insights, streaks, trend

IST = ZoneInfo("Asia/Kolkata")


def at(day: str, hh: int = 13, mm: int = 0) -> float:
    """Epoch for a local IST time on a day like '2026-11-02'."""
    y, m, d = (int(x) for x in day.split("-"))
    return datetime(y, m, d, hh, mm, tzinfo=IST).timestamp()


# 2026-11-02 is a Monday. "now" is Wednesday 4 Nov, 20:00 IST.
NOW = at("2026-11-04", 20)


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def meal(db, day, kcal=2000.0, protein=100.0, hh=13, mm=0):
    db.append("meals", "meal", {"meal": "lunch", "items": [],
                                "total": {"kcal": kcal, "protein": protein, "carbs": 0, "fat": 0}},
              ts=at(day, hh, mm))


def spend(db, day, amount):
    db.append("expenses", "expense", {"amount": amount, "category": "food", "note": ""}, ts=at(day))


def sets(db, day, exercise="Bench Press", weight=60, reps=8):
    db.append("workouts", "set", {"exercise": exercise.lower(), "weight_kg": weight, "reps": reps,
                                  "e1rm": 0, "kcal_burned": 5}, ts=at(day))


def goals(reg, db, **kw):
    reg.call("goals__set_goal", kw, db)


def values(block):
    return {p["date"]: p["value"] for p in block["points"]}


# ---- day boundaries -------------------------------------------------------------------------

def test_a_late_dinner_belongs_to_its_own_local_day(reg, db):
    meal(db, "2026-11-03", kcal=800, hh=23, mm=30)
    v = values(trend(db, reg, "kcal", 3, NOW, "Asia/Kolkata"))
    assert v["2026-11-03"] == 800 and v["2026-11-04"] == 0


def test_just_after_midnight_is_the_next_day_in_ist_but_the_previous_day_in_utc(reg, db):
    meal(db, "2026-11-04", kcal=500, hh=0, mm=10)  # 00:10 IST = 18:40 UTC on the 3rd
    assert values(trend(db, reg, "kcal", 3, NOW, "Asia/Kolkata"))["2026-11-04"] == 500
    assert values(trend(db, reg, "kcal", 3, NOW, "UTC"))["2026-11-03"] == 500


# ---- trends ---------------------------------------------------------------------------------

def test_kcal_trend_points_average_and_hit_percentage(reg, db):
    goals(reg, db, kcal=2000)
    meal(db, "2026-10-31", kcal=2050)   # within 10%: hit
    meal(db, "2026-11-01", kcal=1500)   # under: miss
    meal(db, "2026-11-02", kcal=1900)   # hit
    meal(db, "2026-11-04", kcal=100)    # today, not over yet: excluded from the percentage
    out = trend(db, reg, "kcal", 5, NOW, "Asia/Kolkata")
    assert len(out["points"]) == 5 and out["goal"] == 2000
    # counted: 31 Oct (hit), 1 Nov (miss), 2 Nov (hit), 3 Nov (nothing logged: miss)
    assert out["hit_days"] == 2 and out["counted_days"] == 4 and out["hit_pct"] == 50
    assert out["avg"] == round((2050 + 1500 + 1900 + 100) / 4, 1)  # unlogged days are not zeros


def test_days_before_you_started_tracking_are_not_counted(reg, db):
    goals(reg, db, protein_g=100)
    for day in ("2026-11-01", "2026-11-02", "2026-11-03"):
        meal(db, day, protein=120)
    out = trend(db, reg, "protein", 30, NOW, "Asia/Kolkata")
    assert out["tracked_days"] == 4
    assert out["hit_pct"] == 100 and out["counted_days"] == 3


def test_spend_hit_means_at_or_under_budget_and_a_quiet_day_is_a_hit(reg, db):
    goals(reg, db, daily_spend=500)
    spend(db, "2026-11-01", 400)
    spend(db, "2026-11-02", 700)
    out = trend(db, reg, "spend", 4, NOW, "Asia/Kolkata")
    # tracked from the 1st; counted: 1st (hit), 2nd (miss), 3rd (no spend: hit)
    assert (out["hit_days"], out["counted_days"], out["hit_pct"]) == (2, 3, 67)


def test_no_goal_means_no_hit_percentage(reg, db):
    spend(db, "2026-11-02", 100)
    assert "hit_pct" not in trend(db, reg, "spend", 5, NOW, "Asia/Kolkata")


def test_volume_is_summed_per_muscle_group(reg, db):
    sets(db, "2026-11-02", "Bench Press", 60, 8)
    sets(db, "2026-11-02", "Bench Press", 60, 8)
    sets(db, "2026-11-03", "Barbell Squat", 100, 5)
    out = trend(db, reg, "volume", 5, NOW, "Asia/Kolkata")
    assert out["by_group"]["chest"] == 960 and out["by_group"]["legs"] == 500


def test_workout_days_counts_a_day_once_however_many_sets(reg, db):
    sets(db, "2026-11-02")
    sets(db, "2026-11-02")
    v = values(trend(db, reg, "workout_days", 3, NOW, "Asia/Kolkata"))
    assert v["2026-11-02"] == 1.0 and v["2026-11-03"] == 0.0


def test_voided_entries_are_ignored(reg, db):
    event_id = db.append("meals", "meal", {"meal": "lunch", "items": [],
                         "total": {"kcal": 900, "protein": 10, "carbs": 0, "fat": 0}}, ts=at("2026-11-03"))
    reg.call("events__void_event", {"event_id": event_id}, db)
    assert values(trend(db, reg, "kcal", 3, NOW, "Asia/Kolkata"))["2026-11-03"] == 0


@pytest.mark.parametrize("metric,days", [("nonsense", 14), ("kcal", 0), ("kcal", 9999)])
def test_bad_inputs_return_an_error(reg, db, metric, days):
    assert "error" in trend(db, reg, metric, days, NOW)
    assert "error" in insights(db, reg, 0, NOW) or days != 0


# ---- daily streaks --------------------------------------------------------------------------

def test_meal_streak_counts_consecutive_days_including_today(reg, db):
    for day in ("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04"):
        meal(db, day)
    s = streaks(db, reg, NOW, "Asia/Kolkata")["meals_logged"]
    assert s["current"] == 4 and s["today_done"] is True


def test_an_unfinished_today_does_not_break_the_streak(reg, db):
    for day in ("2026-11-01", "2026-11-02", "2026-11-03"):
        meal(db, day)
    s = streaks(db, reg, NOW, "Asia/Kolkata")["meals_logged"]
    assert s["current"] == 3 and s["today_done"] is False


def test_one_missed_day_a_week_is_forgiven(reg, db):
    meal(db, "2026-11-02")          # Mon
    meal(db, "2026-11-04")          # Wed (Tue missed)
    s = streaks(db, reg, NOW, "Asia/Kolkata")["meals_logged"]
    assert s["current"] == 2 and s["grace_used_this_week"] is True


def test_a_second_miss_in_the_same_week_breaks_the_streak(reg, db):
    friday = at("2026-11-06", 20)
    for day in ("2026-11-02", "2026-11-04", "2026-11-06"):   # Tue and Thu missed
        meal(db, day)
    s = streaks(db, reg, friday, "Asia/Kolkata")["meals_logged"]
    assert s["current"] == 1 and s["best"] == 2


def test_the_grace_day_resets_each_week(reg, db):
    sunday_miss_then_tue_miss = ["2026-10-30", "2026-10-31", "2026-11-02", "2026-11-04"]
    # Sun 1 Nov missed (week 44), Tue 3 Nov missed (week 45): each week has its own grace day
    for day in sunday_miss_then_tue_miss:
        meal(db, day)
    s = streaks(db, reg, NOW, "Asia/Kolkata")["meals_logged"]
    assert s["current"] == 4


def test_protein_streak_needs_a_goal_and_a_target_hit(reg, db):
    for day in ("2026-11-02", "2026-11-03"):
        meal(db, day, protein=80)
    assert streaks(db, reg, NOW, "Asia/Kolkata")["protein_target"] is None
    goals(reg, db, protein_g=100)
    assert streaks(db, reg, NOW, "Asia/Kolkata")["protein_target"]["current"] == 0
    meal(db, "2026-11-04", protein=120)
    assert streaks(db, reg, NOW, "Asia/Kolkata")["protein_target"]["current"] == 1


def test_the_streak_respects_the_timezone_for_a_day_that_straddles_midnight(reg, db):
    meal(db, "2026-11-04", hh=0, mm=10)   # 00:10 IST: 4 Nov in IST, 3 Nov in UTC
    ist = streaks(db, reg, NOW, "Asia/Kolkata")["meals_logged"]
    assert ist["today_done"] is True


def test_no_data_gives_no_streaks(reg, db):
    assert streaks(db, reg, NOW, "Asia/Kolkata") == {
        "meals_logged": None, "protein_target": None, "training": None}


# ---- weekly training streak -----------------------------------------------------------------

def _week(db, *days):
    for day in days:
        sets(db, day)


def test_training_streak_counts_weeks_that_meet_the_goal_and_rest_days_do_not_break_it(reg, db):
    goals(reg, db, workout_days_per_week=3)
    _week(db, "2026-10-19", "2026-10-21", "2026-10-23")   # Mon, Wed, Fri
    _week(db, "2026-10-26", "2026-10-28", "2026-10-30")
    _week(db, "2026-11-02")                               # this week: 1 of 3 so far
    t = streaks(db, reg, NOW, "Asia/Kolkata")["training"]
    assert t["current_weeks"] == 2 and t["this_week"] == {"days": 1, "goal": 3, "met": False}


def test_an_unfinished_week_never_breaks_the_streak_and_a_met_week_extends_it(reg, db):
    goals(reg, db, workout_days_per_week=2)
    _week(db, "2026-10-26", "2026-10-28")
    _week(db, "2026-11-02", "2026-11-03")
    t = streaks(db, reg, NOW, "Asia/Kolkata")["training"]
    assert t["current_weeks"] == 2 and t["this_week"]["met"] is True


def test_a_missed_week_breaks_the_weekly_streak(reg, db):
    goals(reg, db, workout_days_per_week=3)
    _week(db, "2026-10-12", "2026-10-14", "2026-10-16")
    _week(db, "2026-10-19")                                # a short week
    _week(db, "2026-10-26", "2026-10-28", "2026-10-30")
    t = streaks(db, reg, NOW, "Asia/Kolkata")["training"]
    assert t["current_weeks"] == 1 and t["best_weeks"] == 1


def test_no_workout_goal_means_no_training_streak(reg, db):
    _week(db, "2026-11-02")
    assert streaks(db, reg, NOW, "Asia/Kolkata")["training"] is None


def test_insights_bundle_has_every_metric_and_streaks(reg, db):
    meal(db, "2026-11-03")
    out = insights(db, reg, 14, NOW, "Asia/Kolkata")
    assert set(out["metrics"]) == {"kcal", "protein", "spend", "workout_days", "volume"}
    assert out["streaks"]["meals_logged"]["current"] >= 1
