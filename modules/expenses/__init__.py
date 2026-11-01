from app.core import clock
from app.core.module import Module, Tool

CATEGORIES = ["food", "transport", "shopping", "bills", "entertainment", "health", "other"]


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
    entry = {"amount": round(amount, 2), "category": category, "note": note}
    db.append("expenses", "expense", entry)
    return {"logged": entry}


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
