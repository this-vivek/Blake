"""Discovers modules under modules/ and exposes their tools to Claude."""
import importlib
import pkgutil

import modules as modules_pkg
from app.core.module import Module, Tool

CORE_PROMPT = (
    "You are Blake, a personal assistant. Use the provided tools to log and query data. "
    "Never invent nutrition or training numbers; use tool results. "
    "If a message spans several areas, call every relevant tool. Be brief."
)


class Registry:
    def __init__(self, mods: list[Module]):
        self.modules = {m.name: m for m in mods}
        self._tools: dict[str, Tool] = {}
        for m in mods:
            for t in m.tools:
                self._tools[f"{m.name}__{t.name}"] = t

    @classmethod
    def discover(cls, only: list[str] | None = None) -> "Registry":
        mods = []
        for info in pkgutil.iter_modules(modules_pkg.__path__):
            if only and info.name not in only:
                continue
            mods.append(importlib.import_module(f"modules.{info.name}").module)
        return cls(mods)

    def system_prompt(self) -> str:
        return "\n\n".join([CORE_PROMPT] + [m.system_prompt for m in self.modules.values()])

    def tool_specs(self) -> list[dict]:
        return [
            {"name": n, "description": t.description, "input_schema": t.input_schema}
            for n, t in sorted(self._tools.items())
        ]

    def call(self, name: str, args: dict, db) -> dict:
        tool = self._tools.get(name)
        if not tool:
            return {"error": f"unknown tool {name}"}
        try:
            return tool.handler(args, db)
        except Exception as e:  # noqa: BLE001 - never crash the tool loop
            return {"error": f"{type(e).__name__}: {e}"}
