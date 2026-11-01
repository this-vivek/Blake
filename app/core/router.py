"""Claude tool-use loop. `client` is an anthropic.Anthropic (or a fake with the same shape)."""
import json
import os

FAST_MODEL = os.getenv("BLAKE_FAST_MODEL", "claude-haiku-5-5")
SMART_MODEL = os.getenv("BLAKE_SMART_MODEL", "claude-sonnet-5-5")
MAX_STEPS = 6


def run_turn(client, db, registry, content, model: str = FAST_MODEL) -> dict:
    """content: str, or a list of Anthropic content blocks (text + image)."""
    messages = [{"role": "user", "content": content}]
    # cache_control on the stable system prompt enables prompt caching
    system = [{"type": "text", "text": registry.system_prompt(),
               "cache_control": {"type": "ephemeral"}}]
    calls: list[dict] = []
    for _ in range(MAX_STEPS):
        resp = client.messages.create(
            model=model, max_tokens=1024, system=system,
            tools=registry.tool_specs(), messages=messages,
        )
        messages.append({"role": "assistant", "content": resp.content})
        uses = [b for b in resp.content if b.type == "tool_use"]
        if not uses:
            text = "".join(b.text for b in resp.content if b.type == "text")
            return {"text": text, "calls": calls}
        results = []
        for u in uses:
            out = registry.call(u.name, u.input, db)
            calls.append({"name": u.name, "input": u.input, "output": out})
            results.append({"type": "tool_result", "tool_use_id": u.id,
                            "content": json.dumps(out)})
        messages.append({"role": "user", "content": results})
    return {"text": "(stopped: too many tool steps)", "calls": calls}
