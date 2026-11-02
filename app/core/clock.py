"""Single source of 'current time' so modules never call time.time() directly.

Tests monkeypatch `now` to a fixed function instead of depending on wall-clock time.
"""
import os
import time
from datetime import datetime
from zoneinfo import ZoneInfo

DEFAULT_TZ = os.getenv("BLAKE_TZ", "Asia/Kolkata")


def now() -> float:
    return time.time()


def day_start(at: float | None = None, tz_name: str | None = None) -> float:
    """UTC epoch for the start (00:00) of the local calendar day containing `at`."""
    tz = ZoneInfo(tz_name or DEFAULT_TZ)
    at = at if at is not None else now()
    local = datetime.fromtimestamp(at, tz)
    start_local = local.replace(hour=0, minute=0, second=0, microsecond=0)
    return start_local.timestamp()


def local_hour(at: float, tz_name: str | None = None) -> float:
    """Fractional local hour of day (e.g. 13.5 for 1:30 PM) — used to compare "now" against
    a slot's usual log time for the Today screen's nudges (ROADMAP-NEXTGEN.md P3)."""
    tz = ZoneInfo(tz_name or DEFAULT_TZ)
    local = datetime.fromtimestamp(at, tz)
    return local.hour + local.minute / 60
