from app.core import clock
from app.core.module import Module, Tool

CATEGORIES = ["food", "transport", "shopping", "bills", "entertainment", "health", "other"]


def _parse_location(args: dict) -> tuple[dict | None, str | None]:
    """Optional where-it-was-spent point from the app. Returns (location, error). Absent lat
    and lon means no location; one without the other, or out-of-range values, is an error."""
    lat, lon = args.get("lat"), args.get("lon")
    if lat is None and lon is None:
        return None, None
    try:
        lat_f, lon_f = float(lat), float(lon)
    except (TypeError, ValueError):
        return None, "lat and lon must both be numbers"
    if not (-90 <= lat_f <= 90 and -180 <= lon_f <= 180):
        return None, "lat/lon out of range"
    loc = {"lat": round(lat_f, 5), "lon": round(lon_f, 5)}
    acc = args.get("accuracy_m")
    if acc is not None:
        try:
            loc["accuracy_m"] = round(max(float(acc), 0.0), 1)
        except (TypeError, ValueError):
            pass  # accuracy is advisory; a bad value shouldn't block saving the spend
    return loc, None


def log_expense(args: dict, db) -> dict:
    try:
        amount = float(args["amount"])
        category = str(args["category"]).strip().lower()
    except (KeyError, ValueError, TypeError):
        return {"error": "need amount and category"}
    if amount <= 0:
        return {"error": "amount must be positive"}
    if category not in CATEGORIES:
        category = "other"
    note = str(args.get("note", "")).strip()
    location, loc_error = _parse_location(args)
    if loc_error:
        return {"error": loc_error}

    client_id = args.get("client_id")
    if client_id:
        existing = db.find_by_client_id(client_id)
        if existing:  # retried request that already landed — return the original, don't redo it
            return {"logged": existing["payload"], "event_id": existing["id"], "duplicate": True}

    entry = {"amount": round(amount, 2), "category": category, "note": note}
    if location:
        entry["location"] = location
    event_id = db.append("expenses", "expense", entry, client_id=client_id)
    return {"logged": entry, "event_id": event_id}


def daily_summary(args: dict, db) -> dict:
    since = clock.day_start()
    total = 0.0
    by_category: dict[str, float] = {}
    for e in db.query_active("expenses", "expense", since=since):
        amt = e["payload"]["amount"]
        cat = e["payload"]["category"]
        total += amt
        by_category[cat] = round(by_category.get(cat, 0.0) + amt, 2)
    return {"today": {"total": round(total, 2), "by_category": by_category}}


module = Module(
    name="expenses",
    version="0.1.0",
    system_prompt=(
        "Expenses: call log_expense for each purchase/spend mentioned, with amount and "
        f"category ({', '.join(CATEGORIES)}). Totals come from the tool, never estimate."
    ),
    tools=[
        Tool(
            "log_expense",
            "Log a single expense.",
            {"type": "object", "required": ["amount", "category"], "properties": {
                "amount": {"type": "number"},
                "category": {"type": "string", "enum": CATEGORIES},
                "note": {"type": "string"}}},
            log_expense,
        ),
        Tool("daily_summary", "Spending totals for today (local calendar day).",
             {"type": "object", "properties": {}}, daily_summary),
    ],
)
