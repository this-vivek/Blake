from app.core.module import Module, Tool


def void_event(args: dict, db) -> dict:
    try:
        event_id = int(args["event_id"])
    except (KeyError, ValueError, TypeError):
        return {"error": "event_id is required"}
    event = db.get(event_id)
    if not event:
        return {"error": f"no event with id {event_id}"}
    if event["kind"] == "void":
        return {"error": "cannot void a void event"}
    db.append(event["module"], "void", {"target_id": event_id})
    return {"voided": event_id, "was": {
        "module": event["module"], "kind": event["kind"], "payload": event["payload"]}}


module = Module(
    name="events",
    version="0.1.0",
    system_prompt=(
        "events: use void_event to undo/correct a previously logged entry by its numeric id "
        "(shown in a log confirmation). An edit is void_event followed by a fresh log call, "
        "e.g. 'make that 3 rotis' = void the roti entry, then log 3 rotis."
    ),
    tools=[
        Tool(
            "void_event",
            "Void (undo) a previously logged event by id. Use before re-logging a correction.",
            {"type": "object", "required": ["event_id"], "properties": {
                "event_id": {"type": "integer"}}},
            void_event,
        ),
    ],
)
