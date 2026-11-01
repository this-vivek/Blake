.PHONY: install test eval run lint

install:
	pip install -e ".[dev]"

test:
	pytest -q

eval:
	python -m evals.run

run:
	uvicorn app.main:app --reload

lint:
	ruff check .
