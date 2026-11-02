"""Tasks with optional due dates (ROADMAP-NEXTGEN.md P9).

A due date is a calendar date (YYYY-MM-DD, in BLAKE_TZ). Open tasks come back with a
`due_status` (overdue / today / upcoming) so the app can show it and the reminders can use it.
Older tasks whose due text was free-form keep that text and simply have no status.
"""
import datetime as dt
from zoneinfo import ZoneInfo

from app.core import clock
from app.core.module import Module, Tool


def _today() -> dt.date:
    return dt.datetime.fromtimestamp(clock.now(), ZoneInfo(clock.DEFAULT_TZ)).date()


def _parse_due(raw) -> tuple[str | None, str | None]:
    """(iso date or None, error). Empty means no due date."""
    if raw is None or str(raw).strip() == "":
        return None, None
    try:
        return dt.date.fromisoformat(str(raw).strip()).isoformat(), None
    except ValueError:
        return None, "due must be a date like 2026-11-06"


def _status(due: str | None, today: dt.date) -> str | None:
    try:
        d = dt.date.fromisoformat(due) if due else None
    except ValueError:
        return None
    if d is None:
        return None
    return "overdue" if d < today else "today" if d == today else "upcoming"


def _open_tasks(db) -> list[dict]:
    done_ids = {e["payload"]["task_id"] for e in db.query_active("tasks", "task_done", limit=10**6)}
    # the newest due-date change per task wins
    due_changes: dict[int, str | None] = {}
    for e in sorted(db.query_active("tasks", "task_due", limit=10**6), key=lambda e: e["id"]):
        due_changes[e["payload"]["task_id"]] = e["payload"].get("due")
    today = _today()
    out = []
    for e in db.query_active("tasks", "task", limit=10**6):
        if e["id"] in done_ids:
            continue
        due = due_changes.get(e["id"], e["payload"].get("due"))
        out.append({"id": e["id"], "title": e["payload"]["title"], "due": due, "due_status": _status(due, today)})
    # soonest due first, undated last, then oldest first
    out.sort(key=lambda t: (t["due"] is None or t["due_status"] is None, t["due"] or "", t["id"]))
    return out


def add_task(args: dict, db) -> dict:
    title = str(args.get("title", "")).strip()
    if not title:
        return {"error": "title is required"}
    due, error = _parse_due(args.get("due"))
    if error:
        return {"error": error}

    client_id = args.get("client_id")
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:  # retried request that already landed
            return {"logged": {"id": existing["id"], **existing["payload"]}, "duplicate": True}

    entry = {"title": title, "due": due}
    task_id = db.append("tasks", "task", entry, client_id=client_id)
    return {"logged": {"id": task_id, **entry, "due_status": _status(due, _today())}}


def set_task_due(args: dict, db) -> dict:
    try:
        task_id = int(args["task_id"])
    except (KeyError, ValueError, TypeError):
        return {"error": "task_id is required"}
    if not any(t["id"] == task_id for t in _open_tasks(db)):
        return {"error": f"no open task with id {task_id}"}
    due, error = _parse_due(args.get("due"))
    if error:
        return {"error": error}
    db.append("tasks", "task_due", {"task_id": task_id, "due": due})
    return {"updated": {"id": task_id, "due": due, "due_status": _status(due, _today())}}


def _complete(db, match: dict) -> dict:
    db.append("tasks", "task_done", {"task_id": match["id"], "title": match["title"]})
    return {"completed": match}


def complete_task(args: dict, db) -> dict:
    title = str(args.get("title", "")).strip().lower()
    if not title:
        return {"error": "title is required"}
    open_tasks = _open_tasks(db)
    match = next((t for t in open_tasks if t["title"].lower() == title), None)
    if not match:
        match = next((t for t in open_tasks if title in t["title"].lower()), None)
    if not match:
        return {"error": f"no open task matching '{title}'"}
    return _complete(db, match)


def complete_by_id(args: dict, db) -> dict:
    try:
        task_id = int(args["task_id"])
    except (KeyError, ValueError, TypeError):
        return {"error": "task_id is required"}
    match = next((t for t in _open_tasks(db) if t["id"] == task_id), None)
    if not match:
        return {"error": f"no open task with id {task_id}"}
    return _complete(db, match)


def list_tasks(args: dict, db) -> dict:
    return {"open_tasks": _open_tasks(db)}


module = Module(
    name="tasks",
    version="0.2.0",
    system_prompt=(
        "Tasks: add_task to log a new to-do, complete_task to mark one done by matching its "
        "title text, list_tasks to see what's open, set_task_due to change a due date. Due dates "
        "are calendar dates written YYYY-MM-DD: work them out from today's date, which is given "
        "in the context ('Friday' = the next Friday, 'tomorrow' = today plus one day)."
    ),
    tools=[
        Tool(
            "add_task",
            "Add a new to-do item, optionally with a due date.",
            {"type": "object", "required": ["title"], "properties": {
                "title": {"type": "string"},
                "due": {"type": "string", "description": "optional due date as YYYY-MM-DD"}}},
            add_task,
        ),
        Tool(
            "set_task_due",
            "Set or clear the due date of an open task by its numeric id (empty due clears it).",
            {"type": "object", "required": ["task_id"], "properties": {
                "task_id": {"type": "integer"},
                "due": {"type": "string", "description": "YYYY-MM-DD, or empty to clear"}}},
            set_task_due,
        ),
        Tool(
            "complete_task",
            "Mark an open task as done by matching its title.",
            {"type": "object", "required": ["title"], "properties": {
                "title": {"type": "string"}}},
            complete_task,
        ),
        Tool(
            "complete_by_id",
            "Mark an open task as done by its numeric id (from list_tasks).",
            {"type": "object", "required": ["task_id"], "properties": {
                "task_id": {"type": "integer"}}},
            complete_by_id,
        ),
        Tool("list_tasks", "List all open (not completed) tasks, soonest due first.",
             {"type": "object", "properties": {}}, list_tasks),
    ],
)
