import time
from types import SimpleNamespace as NS

import pytest

from app.chat_context import build_content, build_context
from app.core.db import Db
from app.core.registry import Registry
from app.core.router import run_turn


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def log_turn(reg, db, text, items, meal="breakfast", ts=None):
    """Simulates what /chat stores: the tool call and its result inside a chat turn event."""
    out = reg.call("meals__log_meal", {"meal": meal, "items": items}, db)
    db.append("chat", "turn", {
        "text": text, "has_image": False, "text_reply": "ok",
        "calls": [{"name": "meals__log_meal", "input": {"meal": meal, "items": items}, "output": out}],
    }, ts=ts)
    return out["event_id"]


def test_no_history_means_no_context(reg, db):
    assert build_context(db, reg) == ""


def test_context_has_the_last_turn_with_its_event_id_and_todays_meals(reg, db):
    event_id = log_turn(reg, db, "2 roti with dal", [
        {"name": "roti", "qty": 2, "unit": "piece"}, {"name": "dal", "qty": 1, "unit": "katori"}])
    context = build_context(db, reg)
    assert "User: 2 roti with dal" in context
    assert f"event_id {event_id}" in context
    assert "roti 2 piece" in context
    assert "Today so far:" in context and "breakfast: roti 80 g, dal 150 g" in context
    assert "void_event" in context


def test_an_undone_entry_is_marked_and_dropped_from_todays_list(reg, db):
    event_id = log_turn(reg, db, "2 roti", [{"name": "roti", "qty": 2, "unit": "piece"}])
    reg.call("events__void_event", {"event_id": event_id}, db)
    context = build_context(db, reg)
    assert "[already undone]" in context
    assert "breakfast: roti" not in context  # no longer one of today's active meals


def test_old_turns_are_left_out(reg, db):
    log_turn(reg, db, "old lunch", [{"name": "rice", "grams": 100}], ts=time.time() - 3 * 3600)
    assert "old lunch" not in build_context(db, reg)


def test_only_the_last_three_turns_are_included(reg, db):
    for i in range(5):
        log_turn(reg, db, f"turn number {i}", [{"name": "egg", "grams": 50}])
    context = build_context(db, reg)
    assert "turn number 4" in context and "turn number 2" in context
    assert "turn number 1" not in context and "turn number 0" not in context


def test_long_text_is_clipped(reg, db):
    log_turn(reg, db, "very long " * 100, [{"name": "egg", "grams": 50}])
    assert len(build_context(db, reg)) < 2000


def test_content_order_is_photo_then_context_then_the_message():
    image = {"type": "image", "source": {}}
    content = build_content("make that 3 rotis", image, "ctx")
    assert [c["type"] for c in content] == ["image", "text", "text"]
    assert content[1]["text"].startswith("<context>") and content[2]["text"] == "make that 3 rotis"
    assert build_content("hi", None, "") == [{"type": "text", "text": "hi"}]
    assert build_content("", None, "")[0]["text"] == "Log this."


class ScriptedModel:
    """Fake Anthropic client: records every request, replies from a script."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        self.requests.append(kw)
        return self.responses.pop(0)


def test_make_that_3_rotis_is_a_void_plus_a_fresh_log(reg, db):
    old_id = log_turn(reg, db, "2 roti", [{"name": "roti", "qty": 2, "unit": "piece"}], meal="lunch")
    model = ScriptedModel(
        NS(content=[
            NS(type="tool_use", id="t1", name="events__void_event", input={"event_id": old_id}),
            NS(type="tool_use", id="t2", name="meals__log_meal",
               input={"meal": "lunch", "items": [{"name": "roti", "qty": 3, "unit": "piece"}]}),
        ]),
        NS(content=[NS(type="text", text="Changed to 3 rotis.")]),
    )
    content = build_content("make that 3 rotis", None, build_context(db, reg))
    result = run_turn(model, db, reg, content)

    assert f"event_id {old_id}" in model.requests[0]["messages"][0]["content"][0]["text"]
    assert result["text"] == "Changed to 3 rotis."
    active = db.query_active("meals", "meal")
    assert len(active) == 1 and active[0]["payload"]["items"][0]["grams"] == 120.0
    assert old_id not in [e["id"] for e in active]


def test_the_system_prompt_and_tools_do_not_change_when_context_does(reg, db):
    model = ScriptedModel(NS(content=[NS(type="text", text="a")]), NS(content=[NS(type="text", text="b")]))
    run_turn(model, db, reg, build_content("hello", None, build_context(db, reg)))
    log_turn(reg, db, "2 roti", [{"name": "roti", "qty": 2, "unit": "piece"}])
    run_turn(model, db, reg, build_content("hello again", None, build_context(db, reg)))
    first, second = model.requests
    assert first["system"] == second["system"] and first["tools"] == second["tools"]
    assert first["messages"][0]["content"] != second["messages"][0]["content"]
