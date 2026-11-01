from types import SimpleNamespace as NS

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.meal_refresh import refresh_meal_suggestion


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


class FakeClient:
    def __init__(self, text: str):
        self.text = text
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        return NS(content=[NS(type="text", text=self.text)])


class RaisingClient:
    def __init__(self):
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        raise RuntimeError("simulated API failure")


def test_refresh_uses_llm_pick_with_deterministic_macros(reg, db):
    client = FakeClient('[{"name":"egg","grams":100},{"name":"poha","grams":200}]')
    out = refresh_meal_suggestion(client, reg, db, "breakfast")
    assert out["source"] == "llm"
    assert out["total"]["kcal"] == 403.0  # 143 (egg@100g) + 260 (poha@200g), deterministic
    assert {i["name"] for i in out["items"]} == {"egg", "poha"}


def test_refresh_falls_back_on_api_error(reg, db):
    out = refresh_meal_suggestion(RaisingClient(), reg, db, "lunch")
    assert out["source"] == "fallback"
    assert out["meal"] == "lunch"
    assert out["total"]["kcal"] > 0


def test_refresh_falls_back_on_unparseable_response(reg, db):
    client = FakeClient("not json at all")
    out = refresh_meal_suggestion(client, reg, db, "snack")
    assert out["source"] == "fallback"


def test_refresh_falls_back_when_no_items_match_known_foods(reg, db):
    client = FakeClient('[{"name":"unobtainium","grams":100}]')
    out = refresh_meal_suggestion(client, reg, db, "dinner")
    assert out["source"] == "fallback"


def test_refresh_rejects_invalid_meal_type(reg, db):
    assert "error" in refresh_meal_suggestion(FakeClient("[]"), reg, db, "brunch")
