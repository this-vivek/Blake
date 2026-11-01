"""Routing eval against the real API. Checks which tools Claude picks (handlers not executed).

Usage: ANTHROPIC_API_KEY=... python -m evals.run
Add accuracy evals (photo -> macros within tolerance) under evals/ as modules grow.
"""
import json
import os
import pathlib
import sys

import anthropic

from app.core.registry import Registry
from app.core.router import FAST_MODEL


def main() -> int:
    if not os.getenv("ANTHROPIC_API_KEY"):
        print("ANTHROPIC_API_KEY not set")
        return 2
    client = anthropic.Anthropic()
    reg = Registry.discover()
    system = [{"type": "text", "text": reg.system_prompt(),
               "cache_control": {"type": "ephemeral"}}]
    cases = [json.loads(line) for line in
             pathlib.Path(__file__).with_name("routing_cases.jsonl").read_text().splitlines()
             if line.strip()]
    fails = 0
    for c in cases:
        r = client.messages.create(
            model=FAST_MODEL, max_tokens=512, system=system, tools=reg.tool_specs(),
            messages=[{"role": "user", "content": c["text"]}])
        got = {b.name for b in r.content if b.type == "tool_use"}
        ok = set(c["expect"]) <= got
        fails += not ok
        print(("PASS" if ok else "FAIL"), c["text"], "->", sorted(got))
    print(f"{len(cases) - fails}/{len(cases)} passed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
