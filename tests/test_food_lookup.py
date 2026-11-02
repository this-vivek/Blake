from types import SimpleNamespace as NS

import pytest

from app.core.db import Db
from app.food_lookup import lookup_usda, propose_food, propose_with_model, valid_per100
from modules.meals import FOODS, _match, add_custom_food


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
        raise RuntimeError("api down")


def usda_response(**overrides):
    nutrients = [
        {"nutrientName": "Protein", "unitName": "G", "value": 2.9},
        {"nutrientName": "Total lipid (fat)", "unitName": "G", "value": 0.4},
        {"nutrientName": "Carbohydrate, by difference", "unitName": "G", "value": 3.6},
        {"nutrientName": "Energy", "unitName": "KCAL", "value": 23},
        {"nutrientName": "Energy", "unitName": "kJ", "value": 96},
    ]
    food = {"description": "Spinach, raw", "fdcId": 1, "foodNutrients": nutrients, **overrides}
    return {"foods": [food]}


@pytest.fixture
def db():
    return Db(":memory:")


def test_usda_lookup_parses_per_100g_values_and_source():
    out = lookup_usda("spinach", fetch=lambda url: usda_response())
    assert out["per100"] == {"kcal": 23.0, "protein": 2.9, "carbs": 3.6, "fat": 0.4}
    assert out["source"].startswith("USDA FoodData Central: Spinach, raw")
    assert out["proposed"] is True and out["origin"] == "usda"


def test_usda_lookup_skips_entries_missing_a_macro_and_uses_the_next():
    bad = {"description": "Broken", "fdcId": 2, "foodNutrients": [
        {"nutrientName": "Protein", "unitName": "G", "value": 1}]}
    good = usda_response()["foods"][0]
    out = lookup_usda("spinach", fetch=lambda url: {"foods": [bad, good]})
    assert out["source"].startswith("USDA FoodData Central: Spinach, raw")


def _food(description, kcal=100.0, fdc_id=1):
    return {"description": description, "fdcId": fdc_id, "foodNutrients": [
        {"nutrientName": "Protein", "unitName": "G", "value": 3.0},
        {"nutrientName": "Total lipid (fat)", "unitName": "G", "value": 1.0},
        {"nutrientName": "Carbohydrate, by difference", "unitName": "G", "value": 10.0},
        {"nutrientName": "Energy", "unitName": "KCAL", "value": kcal}]}


def test_usda_prefers_the_plain_food_over_processed_products():
    # Real behaviour seen live: the first hit for "spinach" was a soufflé.
    foods = [_food("Spinach souffle", 172, 1), _food("Spinach, canned, drained", 23, 2),
             _food("Spinach, raw", 23, 3)]
    out = lookup_usda("spinach", fetch=lambda url: {"foods": foods})
    assert "Spinach, raw" in out["source"]


def test_usda_rejects_entries_that_do_not_contain_the_food_name():
    # Real behaviour seen live: "dragon fruit" returned candied fruit, "capsicum" cayenne.
    foods = [_food("Candied fruit", 322), _food("Spices, pepper, red or cayenne", 318)]
    assert lookup_usda("dragon fruit", fetch=lambda url: {"foods": foods}) is None
    assert lookup_usda("capsicum", fetch=lambda url: {"foods": [foods[1]]}) is None


def test_usda_uses_the_everyday_name_alias_and_plural_matching():
    foods = [_food("Peppers, bell, green, raw", 23)]
    seen = []

    def fetch(url):
        seen.append(url)
        return {"foods": foods if "bell" in url else []}

    out = lookup_usda("capsicum", fetch=fetch)
    assert out and "Peppers, bell, green, raw" in out["source"]
    assert len(seen) == 2  # the name as typed first, then its alias


def test_usda_lookup_returns_none_on_network_error_or_no_results():
    def boom(url):
        raise OSError("no network")
    assert lookup_usda("spinach", fetch=boom) is None
    assert lookup_usda("spinach", fetch=lambda url: {"foods": []}) is None


def test_usda_request_asks_for_per_100g_datasets_only():
    urls = []

    def fetch(url):
        urls.append(url)
        return {"foods": []}

    lookup_usda("tofu", fetch=fetch, api_key="KEY123")
    assert "SR+Legacy" in urls[0] or "SR%20Legacy" in urls[0]
    assert "api_key=KEY123" in urls[0] and "query=tofu" in urls[0]


def test_model_proposal_is_labelled_unverified():
    out = propose_with_model(FakeClient('{"kcal": 40, "protein": 3, "carbs": 5, "fat": 1}'), "x")
    assert out["origin"] == "model" and "unverified" in out["source"]
    assert out["per100"]["kcal"] == 40.0


@pytest.mark.parametrize("reply", [
    "not json",
    '{"kcal": 40, "protein": 3}',
    '{"kcal": 5000, "protein": 3, "carbs": 5, "fat": 1}',
    '{"kcal": 500, "protein": 60, "carbs": 60, "fat": 60}',
    '{"kcal": 900, "protein": 1, "carbs": 1, "fat": 1}',   # energy doesn't match the macros
])
def test_model_proposal_rejects_garbage_or_implausible_numbers(reply):
    assert propose_with_model(FakeClient(reply), "x") is None


def test_propose_prefers_usda_over_the_model():
    out = propose_food(RaisingClient(), "spinach", fetch=lambda url: usda_response())
    assert out["proposal"]["origin"] == "usda"


def test_propose_falls_back_to_the_model_when_usda_has_nothing():
    out = propose_food(FakeClient('{"kcal": 40, "protein": 3, "carbs": 5, "fat": 1}'), "x",
                       fetch=lambda url: {"foods": []})
    assert out["proposal"]["origin"] == "model"


def test_propose_reports_an_error_when_nothing_works():
    out = propose_food(RaisingClient(), "x", fetch=lambda url: {"foods": []})
    assert "error" in out and "by hand" in out["error"]
    assert "error" in propose_food(RaisingClient(), "  ")


def test_a_proposal_changes_nothing_until_approved(db):
    propose_food(FakeClient('{"kcal": 40, "protein": 3, "carbs": 5, "fat": 1}'), "dragon fruit curry",
                 fetch=lambda url: {"foods": []})
    assert db.query_active("meals", "custom_food") == []
    assert "dragon fruit curry" not in FOODS


def test_approving_stores_the_food_with_its_source_and_it_then_matches(db):
    proposal = propose_food(FakeClient("x"), "spinach", fetch=lambda url: usda_response())["proposal"]
    add_custom_food({"name": "palak paneer test", "per100": proposal["per100"],
                     "source": proposal["source"]}, db)
    stored = db.query_active("meals", "custom_food")[0]["payload"]
    assert stored["source"].startswith("USDA FoodData Central")
    assert _match("palak paneer test", db)[0] == "palak paneer test"


def test_valid_per100_bounds():
    assert valid_per100({"kcal": 100, "protein": 10, "carbs": 10, "fat": 1})
    assert not valid_per100({"kcal": -1, "protein": 1, "carbs": 1, "fat": 1})
    assert not valid_per100({"kcal": 100, "protein": 50, "carbs": 50, "fat": 50})
    assert not valid_per100({"kcal": 100})
