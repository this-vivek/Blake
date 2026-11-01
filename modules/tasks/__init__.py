from app.core.module import Module, Tool


def _open_tasks(db) -> list[dict]:
    tasks = db.query("tasks", "task", limit=1000)
    done_ids = {e["payload"]["task_id"] for e in db.query("tasks", "task_done", limit=1000)}
    return [
        {"id": e["id"], "title": e["payload"]["title"], "due": e["payload"].get("due")}
        for e in tasks if e["id"] not in done_ids
    ]


def add_task(args: dict, db) -> dict:
    title = str(args.get("title", "")).strip()
    if not title:
        return {"error": "title is required"}
    due = args.get("due")
    due = str(due).strip() if due else None
    entry = {"title": title, "due": due}
    task_id = db.append("tasks", "task", entry)
    return {"logged": {"id": task_id, **entry}}


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
    db.append("tasks", "task_done", {"task_id": match["id"], "title": match["title"]})
    return {"completed": match}


def list_tasks(args: dict, db) -> dict:
    return {"open_tasks": _open_tasks(db)}


module = Module(
    name="tasks",
    version="0.1.0",
    system_prompt=(
        "Tasks: add_task to log a new to-do, complete_task to mark one done by matching its "
        "title text, list_tasks to see what's open."
    ),
    tools=[
        Tool(
            "add_task",
            "Add a new to-do item.",
            {"type": "object", "required": ["title"], "properties": {
                "title": {"type": "string"},
                "due": {"type": "string", "description": "optional due date, free text"}}},
            add_task,
        ),
        Tool(
            "complete_task",
            "Mark an open task as done by matching its title.",
            {"type": "object", "required": ["title"], "properties": {
                "title": {"type": "string"}}},
            complete_task,
        ),
        Tool("list_tasks", "List all open (not completed) tasks.",
             {"type": "object", "properties": {}}, list_tasks),
    ],
)
