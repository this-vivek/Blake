FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml ./
COPY app ./app
COPY modules ./modules
COPY evals ./evals

RUN pip install --no-cache-dir .

VOLUME ["/data"]
ENV BLAKE_DB_PATH=/data/blake.db

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
