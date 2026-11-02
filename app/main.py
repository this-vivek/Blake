import base64
import os

import anthropic
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel

from app.core.db import Db
from app.core.registry import Registry
from app.core.router import run_turn
from app.meal_refresh import refresh_meal_suggestion
from app.today import build_today

app = FastAPI(title="Blake")
db = Db(os.getenv("BLAKE_DB_PATH", "blake.db"))
registry = Registry.discover()
client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY

API_KEY = os.getenv("BLAKE_API_KEY")


def require_api_key(x_api_key: str | None = Header(None)) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid or missing X-API-Key")


@app.get("/health")
def health():
    return {"ok": True, "modules": {n: m.version for n, m in registry.modules.items()}}


@app.post("/chat", dependencies=[Depends(require_api_key)])
async def chat(text: str = Form(""), image: UploadFile | None = File(None),
                client_id: str | None = Form(None)):  # noqa: B008
    # client_id must be checked before run_turn, not just before the db.append below — the
    # side effects (log_meal/log_set tool calls) happen inside run_turn, so a retry that only
    # deduped the summary event would still re-run the tools and double-log.
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:
            return existing["payload"]

    content: list[dict] = []
    if image:
        data = base64.b64encode(await image.read()).decode()
        content.append({"type": "image", "source": {
            "type": "base64", "media_type": image.content_type or "image/jpeg", "data": data}})
    content.append({"type": "text", "text": text or "Log this."})
    try:
        result = run_turn(client, db, registry, content)
    except Exception as e:  # noqa: BLE001 - never crash the request on a bad image/API error
        return {"error": f"{type(e).__name__}: {e}"}
    db.append("chat", "turn", {"text": text, "has_image": image is not None, **result},
              client_id=client_id)
    return result


@app.get("/history", dependencies=[Depends(require_api_key)])
def history(module: str, kind: str | None = None, limit: int = 50):
    if module not in registry.modules:
        return {"error": f"unknown module {module}"}
    return {"events": db.query(module, kind=kind, limit=min(limit, 500))}


@app.get("/summary", dependencies=[Depends(require_api_key)])
def summary():
    out: dict[str, dict] = {}
    for name, mod in registry.modules.items():
        tool_names = {t.name for t in mod.tools}
        if "daily_summary" in tool_names:
            out[name] = registry.call(f"{name}__daily_summary", {}, db)
        elif "list_tasks" in tool_names:
            out[name] = registry.call(f"{name}__list_tasks", {}, db)
    return out


@app.get("/suggest/workout", dependencies=[Depends(require_api_key)])
def suggest_workout(location: str, muscle_group: str):
    return registry.call(
        "workouts__suggest_routine", {"location": location, "muscle_group": muscle_group}, db
    )


@app.get("/suggest/meal", dependencies=[Depends(require_api_key)])
def suggest_meal(meal: str):
    return registry.call("meals__suggest_meal", {"meal": meal}, db)


@app.get("/suggest/meal/refresh", dependencies=[Depends(require_api_key)])
def suggest_meal_refresh(meal: str):
    return refresh_meal_suggestion(client, registry, db, meal)


class LogMealItem(BaseModel):
    name: str
    grams: float


class LogMealRequest(BaseModel):
    meal: str
    items: list[LogMealItem]
    client_id: str | None = None  # retry-safe: a duplicate client_id returns the original


@app.post("/log/meal", dependencies=[Depends(require_api_key)])
def log_meal_direct(req: LogMealRequest):
    args = {"meal": req.meal, "items": [i.model_dump() for i in req.items],
             "client_id": req.client_id}
    return registry.call("meals__log_meal", args, db)


class LogSetRequest(BaseModel):
    exercise: str
    weight_kg: float
    reps: int
    client_id: str | None = None


@app.post("/log/set", dependencies=[Depends(require_api_key)])
def log_set_direct(req: LogSetRequest):
    return registry.call("workouts__log_set", req.model_dump(), db)


@app.post("/tasks/{task_id}/complete", dependencies=[Depends(require_api_key)])
def complete_task_direct(task_id: int):
    return registry.call("tasks__complete_by_id", {"task_id": task_id}, db)


@app.post("/events/{event_id}/void", dependencies=[Depends(require_api_key)])
def void_event(event_id: int):
    return registry.call("events__void_event", {"event_id": event_id}, db)


@app.get("/goals", dependencies=[Depends(require_api_key)])
def get_goals():
    return registry.call("goals__get_goals", {}, db)


class GoalsRequest(BaseModel):
    kcal: float | None = None
    protein_g: float | None = None
    daily_spend: float | None = None
    workout_days_per_week: int | None = None
    bodyweight_kg: float | None = None


@app.put("/goals", dependencies=[Depends(require_api_key)])
def put_goals(req: GoalsRequest):
    args = {k: v for k, v in req.model_dump().items() if v is not None}
    return registry.call("goals__set_goal", args, db)


@app.get("/today", dependencies=[Depends(require_api_key)])
def today():
    return build_today(db, registry)
