"""Trends and streaks for the Insights tab (ROADMAP-NEXTGEN.md P6).

Everything here is deterministic arithmetic over the event log: no model is involved. Days
are local calendar days in BLAKE_TZ (a 23:30 dinner belongs to that day, never the next one),
and weeks run Monday to Sunday.

What counts as "hitting" a goal on a day:
  * kcal     - something was logged and the total is within 10% of the target
  * protein  - protein is at or above the target
  * spend    - spend is at or under the daily budget (a day with no spend is a hit)
Training is weekly: sessions on at least `workout_days_per_week` days in the week.

Percentages and averages only cover the days since you started tracking inside the window
(a new user is not penalised for the weeks before the first log), and today is left out of
hit percentages because it is not over yet.
"""
from collections import defaultdict
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from app.core import clock
from modules import body as body_module
from modules import habits as habits_module
from modules import sleep as sleep_module
from modules.workouts import muscle_groups_for

METRICS = ["kcal", "protein", "spend", "workout_days", "volume"]
KCAL_TOLERANCE = 0.10
MAX_DAYS = 180
STREAK_LOOKBACK_DAYS = 400


def _tz(tz_name: str | None) -> ZoneInfo:
    return ZoneInfo(tz_name or clock.DEFAULT_TZ)


def local_date(ts: float, tz: ZoneInfo) -> date:
    return datetime.fromtimestamp(ts, tz).date()


def _start_ts(d: date, tz: ZoneInfo) -> float:
    return datetime(d.year, d.month, d.day, tzinfo=tz).timestamp()


def window_dates(now: float, days: int, tz: ZoneInfo) -> list[date]:
    """The last `days` local dates, oldest first, ending today (calendar arithmetic, so DST
    days are never 23 or 25 hours off)."""
    today = local_date(now, tz)
    return [today - timedelta(days=i) for i in range(days - 1, -1, -1)]


def daily_totals(db, dates: list[date], tz: ZoneInfo) -> dict[date, dict]:
    since = _start_ts(dates[0], tz)
    out = {
        d: {"kcal": 0.0, "protein": 0.0, "spend": 0.0, "sets": 0, "meals": 0,
            "volume": defaultdict(float)}
        for d in dates
    }
    for e in db.query_active("meals", "meal", since=since, limit=10**6):
        d = local_date(e["ts"], tz)
        if d in out:
            total = e["payload"].get("total", {})
            out[d]["kcal"] += float(total.get("kcal", 0.0))
            out[d]["protein"] += float(total.get("protein", 0.0))
            out[d]["meals"] += 1
    for e in db.query_active("expenses", "expense", since=since, limit=10**6):
        d = local_date(e["ts"], tz)
        if d in out:
            out[d]["spend"] += float(e["payload"].get("amount", 0.0))
    for e in db.query_active("workouts", "set", since=since, limit=10**6):
        d = local_date(e["ts"], tz)
        if d in out:
            p = e["payload"]
            groups = muscle_groups_for({p.get("exercise", "")})
            out[d]["sets"] += 1
            out[d]["volume"][groups[0] if groups else "other"] += float(p.get("weight_kg", 0)) * int(p.get("reps", 0))
    return out


def _tracking_start(totals: dict[date, dict]) -> date | None:
    for d in sorted(totals):
        t = totals[d]
        if t["meals"] or t["sets"] or t["spend"]:
            return d
    return None


def _hit(metric: str, t: dict, goal: float | None) -> bool | None:
    """True/False for a day against the goal, None when the metric has no goal."""
    if not goal:
        return None
    if metric == "kcal":
        return t["meals"] > 0 and abs(t["kcal"] - goal) <= goal * KCAL_TOLERANCE
    if metric == "protein":
        return t["protein"] >= goal
    if metric == "spend":
        return t["spend"] <= goal
    return None


def _value(metric: str, t: dict) -> float:
    if metric == "workout_days":
        return 1.0 if t["sets"] else 0.0
    if metric == "volume":
        return sum(t["volume"].values())
    return t[metric]


def _goal_for(metric: str, goals: dict) -> float | None:
    return {"kcal": goals.get("kcal"), "protein": goals.get("protein_g"),
            "spend": goals.get("daily_spend")}.get(metric)


def trend(db, registry, metric: str, days: int, now: float | None = None,
          tz_name: str | None = None) -> dict:
    if metric not in METRICS:
        return {"error": f"metric must be one of {', '.join(METRICS)}"}
    if not 1 <= days <= MAX_DAYS:
        return {"error": f"days must be between 1 and {MAX_DAYS}"}
    tz = _tz(tz_name)
    now = clock.now() if now is None else now
    dates = window_dates(now, days, tz)
    totals = daily_totals(db, dates, tz)
    goals = registry.call("goals__get_goals", {}, db)
    goal = _goal_for(metric, goals)
    start = _tracking_start(totals)

    points = [{"date": d.isoformat(), "value": round(_value(metric, totals[d]), 1),
               "logged": bool(totals[d]["meals"] or totals[d]["sets"] or totals[d]["spend"])}
              for d in dates]
    tracked = [d for d in dates if start is not None and d >= start]
    counted = [d for d in tracked if d != dates[-1]] if len(dates) > 1 else tracked
    values = [_value(metric, totals[d]) for d in tracked]
    if metric in ("kcal", "protein"):
        values = [v for v in values if v > 0]
    hits = [_hit(metric, totals[d], goal) for d in counted]
    hits = [h for h in hits if h is not None]
    out = {
        "metric": metric, "days": days, "points": points,
        "goal": goal,
        "avg": round(sum(values) / len(values), 1) if values else 0.0,
        "tracked_days": len(tracked),
    }
    if goal is not None:
        out["hit_days"] = sum(hits)
        out["counted_days"] = len(hits)
        out["hit_pct"] = round(100 * sum(hits) / len(hits)) if hits else None
    if metric == "volume":
        by_group: dict[str, float] = defaultdict(float)
        for d in dates:
            for g, v in totals[d]["volume"].items():
                by_group[g] += v
        out["by_group"] = {g: round(v) for g, v in sorted(by_group.items())}
    return out


def _streak_with_grace(days: list[tuple[date, bool]]) -> tuple[int, int, set]:
    """Current and best run of hit days. One missed day per ISO week is forgiven (the run is
    neither extended nor broken); a second miss in the same week ends it."""
    streak = best = 0
    grace_weeks: set = set()
    for d, hit in days:
        if hit:
            streak += 1
        else:
            week = d.isocalendar()[:2]
            if week not in grace_weeks:
                grace_weeks.add(week)
            else:
                streak = 0
        best = max(best, streak)
    return streak, best, grace_weeks


def streaks(db, registry, now: float | None = None, tz_name: str | None = None) -> dict:
    tz = _tz(tz_name)
    now = clock.now() if now is None else now
    dates = window_dates(now, STREAK_LOOKBACK_DAYS, tz)
    today = dates[-1]
    totals = daily_totals(db, dates, tz)
    goals = registry.call("goals__get_goals", {}, db)
    start = _tracking_start(totals)
    this_week = today.isocalendar()[:2]
    out: dict = {"meals_logged": None, "protein_target": None, "training": None}
    if start is None:
        return out

    def daily(hit_of) -> dict:
        history = [(d, hit_of(totals[d])) for d in dates if d >= start]
        today_done = history[-1][1]
        if not today_done:
            history = history[:-1]  # today is not over: it neither extends nor breaks the run
        current, best, grace_weeks = _streak_with_grace(history)
        return {"current": current, "best": best, "today_done": today_done,
                "grace_used_this_week": this_week in grace_weeks}

    out["meals_logged"] = daily(lambda t: t["meals"] > 0)
    if goals.get("protein_g"):
        out["protein_target"] = daily(lambda t: t["protein"] >= goals["protein_g"])

    weekly_goal = goals.get("workout_days_per_week")
    if weekly_goal:
        days_by_week: dict[tuple, int] = defaultdict(int)
        for d in dates:
            if d >= start and totals[d]["sets"]:
                days_by_week[d.isocalendar()[:2]] += 1
        weeks = []
        cursor = start - timedelta(days=start.weekday())  # Monday of the first tracked week
        while cursor <= today:
            weeks.append(cursor.isocalendar()[:2])
            cursor += timedelta(days=7)
        met = [days_by_week.get(w, 0) >= weekly_goal for w in weeks]
        this_week_met = met[-1]
        history = met if this_week_met else met[:-1]  # an unfinished week never breaks the run
        current = best = run = 0
        for hit in history:
            run = run + 1 if hit else 0
            best = max(best, run)
        current = run
        out["training"] = {
            "current_weeks": current, "best_weeks": best,
            "this_week": {"days": days_by_week.get(this_week, 0), "goal": weekly_goal,
                          "met": this_week_met},
        }
    return out


def habits_block(db, days: int, now: float, tz) -> list[dict]:
    """Per habit: the value each day, how often it was done, and its streak."""
    today = local_date(now, tz)
    dates = [today - timedelta(days=i) for i in range(days - 1, -1, -1)]
    out = []
    for name, habit in sorted(habits_module.definitions(db).items()):
        values = habits_module.daily_values(db, name)
        first = min(values) if values else None
        counted = [d for d in dates if first is not None and d >= first and d != today]
        done = [d for d in counted if habits_module.is_done(habit, values.get(d, 0.0))]
        out.append({
            "name": name, "kind": habit["kind"], "target": habit.get("target"), "unit": habit.get("unit", ""),
            "points": [{"date": d.isoformat(), "value": values.get(d, 0.0),
                        "done": habits_module.is_done(habit, values.get(d, 0.0))} for d in dates],
            "hit_days": len(done), "counted_days": len(counted),
            "hit_pct": round(100 * len(done) / len(counted)) if counted else None,
            "streak": habits_module.streak(habit, values, today),
        })
    return out


def weight_block(db, days: int, now: float, tz) -> dict:
    points = body_module.trend_points(db, days, today=local_date(now, tz))
    out: dict = {"points": points}
    if points:
        out.update({"latest_kg": points[-1]["kg"], "avg7": points[-1]["avg7"],
                    "change_kg": round(points[-1]["kg"] - points[0]["kg"], 1)})
    return out


def sleep_block(db, days: int, now: float, tz) -> dict:
    points = sleep_module.points(db, days, today=local_date(now, tz))
    out: dict = {"points": points, "nights": len(points)}
    if points:
        out["avg_hours"] = round(sum(p["hours"] for p in points) / len(points), 1)
        rated = [p["quality"] for p in points if p["quality"]]
        if rated:
            out["avg_quality"] = round(sum(rated) / len(rated), 1)
    return out


def insights(db, registry, days: int = 14, now: float | None = None,
             tz_name: str | None = None) -> dict:
    """Everything the Insights tab shows, in one call."""
    if not 1 <= days <= MAX_DAYS:
        return {"error": f"days must be between 1 and {MAX_DAYS}"}
    tz = _tz(tz_name)
    at = clock.now() if now is None else now
    return {
        "days": days,
        "metrics": {m: trend(db, registry, m, days, now, tz_name) for m in METRICS},
        "streaks": streaks(db, registry, now, tz_name),
        "habits": habits_block(db, days, at, tz),
        "weight": weight_block(db, days, at, tz),
        "sleep": sleep_block(db, days, at, tz),
    }
