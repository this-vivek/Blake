"""Bulk "undo" for the Settings screen. Nothing is deleted: every active log entry gets a
void event, same as a single Undo, so the event log stays append-only and every aggregation
(which reads through query_active) sees the day or the whole history as empty.

Goals, the weekly program, custom foods and recipes are never touched.
"""
from app.core import clock

# What counts as "today's logs". Completing a task or a program day is a log too; voiding it
# reopens the task / moves the program pointer back.
DAY_KINDS = {
    "meals": ["meal", "unmatched"],
    "workouts": ["set", "warmup", "program_day_done"],
    "expenses": ["expense"],
    "tasks": ["task_done"],
    "habits": ["habit_log"],
    "body": ["weight"],
    "sleep": ["sleep"],
}

# Everything that is a log rather than a setting.
LOG_KINDS = {
    "meals": ["meal", "unmatched"],
    "workouts": ["set", "warmup", "program_day_done"],
    "expenses": ["expense"],
    "tasks": ["task", "task_done"],
    "habits": ["habit_log"],
    "body": ["weight"],
    "sleep": ["sleep"],
    "chat": ["turn"],
    "insights": ["weekly_review"],  # derived from the logs above, so it goes with them
}


def _void_all(db, kinds: dict[str, list[str]], since: float | None) -> dict:
    voided = 0
    for module, module_kinds in kinds.items():
        for kind in module_kinds:
            for event in db.query_active(module, kind, since=since, limit=10**6):
                db.append(module, "void", {"target_id": event["id"], "reason": "reset"})
                voided += 1
    return {"voided": voided}


def reset_day(db) -> dict:
    return _void_all(db, DAY_KINDS, since=clock.day_start())


def reset_logs(db) -> dict:
    return _void_all(db, LOG_KINDS, since=None)
