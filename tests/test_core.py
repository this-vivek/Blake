from types import SimpleNamespace as NS

import pytest

from app.core.db import Db
from app.core.registry import Registry
from app.core.router import run_turn
from modules.meals import add_custom_food, list_unmatched, protein_gap_foods, resolve_unmatched
from modules.workouts import complete_program_day, today_program_day


@pytest.fixture
def db():
    return Db(":memory:")


@pytest.fixture
def reg():
    return Registry.discover()


def test_db_roundtrip(db):
    db.append("x", "k", {"a": 1})
    assert db.query("x", "k")[0]["payload"] == {"a": 1}


def test_registry_discovers_and_namespaces(reg):
    names = {t["name"] for t in reg.tool_specs()}
    assert {"meals__log_meal", "workouts__log_set"} <= names


def test_unknown_tool_is_error_not_crash(reg, db):
    assert "error" in reg.call("nope__x", {}, db)


def test_meal_macros_from_lookup_not_model(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "egg", "grams": 100}]}, db)
    assert out["total"]["kcal"] == 143.0
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 143.0


def test_unmatched_item_appears_in_unmatched_queue(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 150}]}, db)
    out = list_unmatched(db)
    assert out["items"][0]["name"] == "dragonfruit"
    assert out["items"][0]["grams"] == 150.0


def test_add_custom_food_then_log_meal_matches_it(reg, db):
    add_custom_food({"name": "dragonfruit", "per100": {"kcal": 60, "protein": 1.2, "carbs": 13, "fat": 0.4}}, db)
    out = reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 100}]}, db)
    assert out["logged"][0]["kcal"] == 60.0


def test_add_custom_food_rejects_incomplete_per100(reg, db):
    assert "error" in add_custom_food({"name": "x", "per100": {"kcal": 60}}, db)


def test_resolve_unmatched_maps_to_existing_food_and_voids_queue_item(reg, db):
    reg.call("meals__log_meal", {"meal": "snack", "items": [{"name": "dragonfruit", "grams": 150}]}, db)
    unmatched_id = list_unmatched(db)["items"][0]["id"]

    out = resolve_unmatched({"unmatched_id": unmatched_id, "mapped_name": "mango"}, reg, db)
    assert out["resolved"] is True
    assert out["logged"][0]["name"] == "mango"
    assert list_unmatched(db)["items"] == []  # voided, no longer queued


def test_resolve_unmatched_rejects_unknown_unmatched_id(reg, db):
    assert "error" in resolve_unmatched({"unmatched_id": 999999, "mapped_name": "mango"}, reg, db)


def test_resolve_unmatched_still_unknown_mapping_does_not_void(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 150}]}, db)
    unmatched_id = list_unmatched(db)["items"][0]["id"]

    out = resolve_unmatched({"unmatched_id": unmatched_id, "mapped_name": "also unknown"}, reg, db)
    assert "error" in out
    # the original item is still queued (not voided) since the mapping attempt failed
    assert any(i["id"] == unmatched_id for i in list_unmatched(db)["items"])


def test_define_recipe_computes_per100_from_ingredients(reg, db):
    out = reg.call("meals__define_recipe", {
        "name": "my poha",
        "ingredients": [{"name": "poha", "grams": 200}, {"name": "egg", "grams": 100}],
    }, db)
    # poha 200g: kcal 260, protein 5.4, carbs 48, fat 6; egg 100g: kcal 143, protein 12.6,
    # carbs 0.7, fat 9.5 -> combined / 300g * 100
    assert out["per100"]["kcal"] == round((260 + 143) / 300 * 100, 1)


def test_define_recipe_rejects_unknown_ingredient(reg, db):
    out = reg.call("meals__define_recipe", {
        "name": "my dish", "ingredients": [{"name": "dragonfruit", "grams": 100}],
    }, db)
    assert "error" in out


def test_log_meal_matches_user_defined_recipe_by_name(reg, db):
    recipe = reg.call("meals__define_recipe", {
        "name": "my poha", "ingredients": [{"name": "poha", "grams": 200}, {"name": "egg", "grams": 100}],
    }, db)
    out = reg.call("meals__log_meal", {"items": [{"name": "my poha", "grams": 300}]}, db)
    assert out["logged"][0]["name"] == "my poha"
    assert out["logged"][0]["kcal"] == round(recipe["per100"]["kcal"] * 3, 1)


def test_log_meal_recipe_overrides_static_food_of_same_name(reg, db):
    reg.call("meals__define_recipe", {
        "name": "poha", "ingredients": [{"name": "poha", "grams": 100}, {"name": "peanut", "grams": 50}],
    }, db)
    out = reg.call("meals__log_meal", {"items": [{"name": "poha", "grams": 150}]}, db)
    # the custom "poha" recipe (poha+peanut) should win over the static plain-poha entry
    static_poha_kcal_per100 = 130
    assert out["logged"][0]["kcal"] != round(static_poha_kcal_per100 * 1.5, 1)


def test_protein_gap_foods_includes_custom_recipes(reg, db):
    reg.call("meals__define_recipe", {
        "name": "protein shake", "ingredients": [{"name": "whey protein", "grams": 30}, {"name": "milk", "grams": 200}],
    }, db)
    reg.call("meals__log_meal", {"meal": "snack", "items": [{"name": "protein shake", "grams": 230}]}, db)
    foods = protein_gap_foods(db)
    assert "protein shake" in foods


def test_meal_matching_prefers_dish_name_over_ingredient_word(reg, db):
    # Regression case called out in ROADMAP-NEXTGEN.md P5: "egg curry" (2 words) should win
    # over "tomato" or "onion" (single ingredient words also present in the FOODS table).
    out = reg.call(
        "meals__log_meal", {"items": [{"name": "egg curry with onion and tomato", "grams": 200}]}, db
    )
    assert out["logged"][0]["name"] == "egg curry"


@pytest.mark.parametrize("name", [
    "sweet potato", "boiled sweet potato", "baked sweet potato (skin on)", "sweet potato, chopped",
])
def test_meal_matching_keeps_sweet_potato_distinct_from_potato(reg, db, name):
    # Issue #1: "sweet" was a noise word, so any qualified input collapsed to plain "potato".
    out = reg.call("meals__log_meal", {"items": [{"name": name, "grams": 100}]}, db)
    item = out["logged"][0]
    assert item["name"] == "sweet potato"
    assert (item["kcal"], item["carbs"]) == (86.0, 20.1)


def test_meal_matching_still_strips_noise_when_nothing_matches_raw(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "boiled egg", "grams": 100}]}, db)
    assert out["logged"][0]["name"] == "egg"


def test_log_meal_converts_household_unit_to_grams(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "roti", "qty": 2, "unit": "piece"}]}, db)
    assert out["logged"][0]["grams"] == 80.0  # 2 x 40g per roti


def test_log_meal_converts_generic_unit_regardless_of_food(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "rice", "qty": 0.15, "unit": "kg"}]}, db)
    assert out["logged"][0]["grams"] == 150.0


def test_log_meal_unknown_unit_for_food_is_unmatched_not_guessed(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "roti", "qty": 2, "unit": "slice"}]}, db)
    assert out["unknown_items"] == ["roti"]


def test_log_meal_explicit_grams_still_works_alongside_qty_unit(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "egg", "grams": 100}]}, db)
    assert out["logged"][0]["grams"] == 100.0


def test_log_meal_retry_with_same_client_id_does_not_double_log(reg, db):
    args = {"items": [{"name": "egg", "grams": 100}], "client_id": "retry-1"}
    first = reg.call("meals__log_meal", args, db)
    second = reg.call("meals__log_meal", args, db)
    assert first["total"]["kcal"] == 143.0
    assert second["total"]["kcal"] == 143.0
    assert second.get("duplicate") is True
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 143.0  # not 286.0


def test_voided_meal_excluded_from_daily_summary(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "egg", "grams": 100}]}, db)
    meal_id = db.query("meals", "meal")[0]["id"]
    reg.call("events__void_event", {"event_id": meal_id}, db)
    assert reg.call("meals__daily_summary", {}, db)["today"]["kcal"] == 0.0


def test_daily_summary_uses_local_calendar_day_not_rolling_24h(reg, db, monkeypatch):
    import datetime
    import zoneinfo

    from app.core import clock

    ist = zoneinfo.ZoneInfo("Asia/Kolkata")
    # A dinner logged at 21:00 IST yesterday is only ~3.5h before "now" (00:32 IST today) —
    # well within a rolling 24h window, but it belongs to yesterday's calendar day.
    yesterday_9pm = datetime.datetime(2024, 3, 14, 21, 0, tzinfo=ist).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=ist).timestamp()

    db.append("meals", "meal", {
        "meal": "dinner", "items": [],
        "total": {"kcal": 500.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
    }, ts=yesterday_9pm)

    monkeypatch.setattr(clock, "now", lambda: today_0032)
    out = reg.call("meals__daily_summary", {}, db)
    assert out["today"]["kcal"] == 0.0


def test_daily_summary_includes_meal_logged_just_after_local_midnight(reg, db, monkeypatch):
    import datetime
    import zoneinfo

    from app.core import clock

    ist = zoneinfo.ZoneInfo("Asia/Kolkata")
    today_0005 = datetime.datetime(2024, 3, 15, 0, 5, tzinfo=ist).timestamp()
    today_0032 = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=ist).timestamp()

    db.append("meals", "meal", {
        "meal": "snack", "items": [],
        "total": {"kcal": 120.0, "protein": 0.0, "carbs": 0.0, "fat": 0.0},
    }, ts=today_0005)

    monkeypatch.setattr(clock, "now", lambda: today_0032)
    out = reg.call("meals__daily_summary", {}, db)
    assert out["today"]["kcal"] == 120.0


def test_unknown_food_reported(reg, db):
    out = reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 50}]}, db)
    assert out["unknown_items"] == ["dragonfruit"] and out["logged"] == []


def test_unknown_food_queued_for_curation(reg, db):
    reg.call("meals__log_meal", {"items": [{"name": "dragonfruit", "grams": 50}]}, db)
    queued = db.query("meals", "unmatched")
    assert queued[0]["payload"] == {"name": "dragonfruit", "grams": 50.0}


def test_meal_rejects_bad_input(reg, db):
    assert "error" in reg.call("meals__log_meal", {"items": []}, db)


def test_parenthetical_ingredients_dont_cause_false_match(reg, db):
    # "onion" appears inside the parenthetical description but the dish itself is poha,
    # not onion — matching must not pick an ingredient mentioned in the aside.
    out = reg.call("meals__log_meal", {"items": [
        {"name": "poha (cooked flattened rice with turmeric, onion, peanuts)", "grams": 200}
    ]}, db)
    assert out["logged"][0]["name"] == "poha"
    assert out["unknown_items"] == []


def test_pr_detection(reg, db):
    a = reg.call("workouts__log_set", {"exercise": "Bench", "weight_kg": 60, "reps": 8}, db)
    b = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 65, "reps": 8}, db)
    c = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 50, "reps": 8}, db)
    assert a["first_time"] and not a["is_pr"]
    assert b["is_pr"] and not c["is_pr"]


def test_voided_set_excluded_from_pr_detection(reg, db):
    reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 65, "reps": 8}, db)
    pr_set_id = db.query("workouts", "set")[0]["id"]
    reg.call("events__void_event", {"event_id": pr_set_id}, db)
    # the 65kg PR is voided, so logging 60kg again should look like a fresh first-time lift
    out = reg.call("workouts__log_set", {"exercise": "bench", "weight_kg": 60, "reps": 8}, db)
    assert out["first_time"] and not out["is_pr"]


def test_log_set_retry_with_same_client_id_does_not_double_log(reg, db):
    args = {"exercise": "squat", "weight_kg": 80, "reps": 5, "client_id": "set-retry-1"}
    first = reg.call("workouts__log_set", args, db)
    second = reg.call("workouts__log_set", args, db)
    assert first["e1rm"] == second["e1rm"]
    assert second.get("duplicate") is True
    assert len(db.query("workouts", "set")) == 1


def test_log_set_estimates_kcal_burned_deterministically(reg, db):
    out = reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    # chest MET 5.0, default bodyweight 70kg, 0.75min: 5.0*70*3.5/200*0.75 = 4.59 -> 4.6
    assert out["kcal_burned"] == 4.6


def test_log_set_kcal_burned_uses_bodyweight_goal(reg, db):
    reg.call("goals__set_goal", {"bodyweight_kg": 100}, db)
    out = reg.call("workouts__log_set", {"exercise": "bench press", "weight_kg": 60, "reps": 8}, db)
    # 5.0*100*3.5/200*0.75 = 6.5625 -> 6.6
    assert out["kcal_burned"] == 6.6


def test_log_set_unknown_exercise_falls_back_to_default_met(reg, db):
    out = reg.call("workouts__log_set", {"exercise": "made up lift", "weight_kg": 60, "reps": 8}, db)
    # DEFAULT_MET 5.0, default bodyweight 70kg: same as chest's MET in this case
    assert out["kcal_burned"] == 4.6


def test_default_program_has_no_rest_days(reg, db):
    out = reg.call("workouts__get_program", {}, db)
    assert all(d["type"] == "train" for d in out["days"])
    assert out["days"][0]["muscle_group"] == "chest"


def test_set_program_validates_day_shape(reg, db):
    assert "error" in reg.call("workouts__set_program", {"days": [{"type": "bogus"}]}, db)
    assert "error" in reg.call("workouts__set_program", {"days": [{"type": "train"}]}, db)
    assert "error" in reg.call(
        "workouts__set_program", {"days": [{"type": "train", "muscle_group": "wings"}]}, db
    )


def test_set_program_then_get_program_roundtrips(reg, db):
    days = [{"type": "train", "muscle_group": "chest"}, {"type": "rest"}]
    reg.call("workouts__set_program", {"days": days}, db)
    assert reg.call("workouts__get_program", {}, db)["days"] == days


def test_today_program_day_starts_at_index_zero(reg, db):
    out = today_program_day(db)
    assert out["index"] == 0
    assert out["day"]["muscle_group"] == "chest"


def test_complete_program_day_advances_the_pointer(reg, db):
    complete_program_day({}, db)
    out = today_program_day(db)
    assert out["index"] == 1
    assert out["day"]["muscle_group"] == "back"


def test_today_program_day_wraps_around(reg, db):
    reg.call("workouts__set_program", {"days": [
        {"type": "train", "muscle_group": "chest"}, {"type": "rest"},
    ]}, db)
    complete_program_day({}, db)
    complete_program_day({}, db)
    out = today_program_day(db)
    assert out["index"] == 0  # wrapped after completing both days of a 2-day program


def test_skipping_a_day_does_not_advance_pointer(reg, db):
    out = today_program_day(db)
    assert out["index"] == 0  # no completion ever logged -> still on day 0


class FakeClient:
    """Scripted: first call -> tool_use, second -> final text."""
    def __init__(self):
        self.n = 0
        self.messages = NS(create=self._create)

    def _create(self, **kw):
        self.n += 1
        if self.n == 1:
            return NS(content=[NS(type="tool_use", id="t1", name="workouts__log_set",
                                  input={"exercise": "squat", "weight_kg": 100, "reps": 5})])
        return NS(content=[NS(type="text", text="Logged.")])


def test_router_loop_executes_tools(reg, db):
    res = run_turn(FakeClient(), db, reg, "squat 100x5")
    assert res["text"] == "Logged."
    assert res["calls"][0]["name"] == "workouts__log_set"
    assert db.query("workouts", "set")


def test_run_turn_never_returns_raw_message_content(reg, db):
    # app/main.py persists {"text": ..., "has_image": ..., **run_turn(...)} as the "chat"/
    # "turn" event — run_turn's return value must never carry the raw content blocks (which
    # is where a base64-encoded photo would live), or a photo log would blow through Neon's
    # free storage tier. Only "text" and "calls" (tool name/input/output) may appear.
    fake_image_data = "a" * 2000  # stand-in for what a base64 JPEG looks like
    content = [
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                      "data": fake_image_data}},
        {"type": "text", "text": "ate 2 eggs"},
    ]
    res = run_turn(FakeClient(), db, reg, content)
    assert set(res.keys()) <= {"text", "calls"}
    assert fake_image_data not in str(res)
