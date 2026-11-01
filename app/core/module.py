"""Module contract. Every feature area implements this and exports `module`."""
from collections.abc import Callable
from dataclasses import dataclass, field

from app.core.db import Db

Handler = Callable[[dict, Db], dict]


@dataclass
class Tool:
    name: str
    description: str
    input_schema: dict
    handler: Handler


@dataclass
class Module:
    name: str
    version: str
    system_prompt: str  # short, stable; appended to the core prompt
    tools: list[Tool] = field(default_factory=list)
