import base64
import os

import anthropic
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile

from app.core.db import Db
from app.core.registry import Registry
from app.core.router import run_turn

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
async def chat(text: str = Form(""), image: UploadFile | None = File(None)):  # noqa: B008
    content: list[dict] = []
    if image:
        data = base64.b64encode(await image.read()).decode()
        content.append({"type": "image", "source": {
            "type": "base64", "media_type": image.content_type or "image/jpeg", "data": data}})
    content.append({"type": "text", "text": text or "Log this."})
    return run_turn(client, db, registry, content)
