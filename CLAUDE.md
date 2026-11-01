# Blake

Personal assistant app: Claude routes free text/photos to pluggable modules (meals, workouts, later expenses, tasks, ...).

## Layout
- `app/core/` — db (append-only events), module contract, registry, Claude router. Do not put module logic here.
- `modules/<name>/__init__.py` — exports `module: Module`. One folder per module; tests in `tests/test_<name>.py`.
- `evals/` — routing/accuracy evals that hit the real API. Never run in `make test`.

## Rules
- New feature = new module (or new tool in one module). Core changes only for contract changes.
- Claude extracts structure; **numbers come from lookup tools**, never from model guesses (macros, PR detection).
- Tool names are namespaced `<module>__<tool>`.
- Handlers: `(args: dict, db: Db) -> dict`, must be deterministic and return JSON-serialisable output; validate input, return `{"error": ...}` instead of raising.
- Persist via `db.append(module, kind, payload)`. No per-module tables until a view is needed.
- Models: logging/parsing = Haiku (`BLAKE_FAST_MODEL`), coaching/review = Sonnet (`BLAKE_SMART_MODEL`).
- Keep system prompts and tool schemas stable (prompt caching).

## Commands
- `make install` · `make test` (offline, fake client) · `make eval` (needs ANTHROPIC_API_KEY) · `make run`

## Adding a module
1. Copy `modules/workouts/` → `modules/<name>/`, edit tools + prompt.
2. Add tests (handlers + at least 5 routing cases in `evals/routing_cases.jsonl`).
3. `make test && make eval` before committing.
