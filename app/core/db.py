"""Append-only event store. Modules write events; views are derived by querying.

Uses Postgres when DATABASE_URL is set (persistent, for deployment), otherwise SQLite
(fast, no setup, used by tests and local `make run`).
"""
import json
import os
import time


class Db:
    def __init__(self, path: str = "blake.db"):
        database_url = os.getenv("DATABASE_URL")
        self.backend = "postgres" if database_url else "sqlite"
        if self.backend == "postgres":
            import psycopg2

            self.conn = psycopg2.connect(database_url)
            self.conn.autocommit = True
            self._ph = "%s"
            with self.conn.cursor() as cur:
                cur.execute(
                    """CREATE TABLE IF NOT EXISTS events (
                        id SERIAL PRIMARY KEY,
                        module TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        ts DOUBLE PRECISION NOT NULL,
                        payload TEXT NOT NULL
                    )"""
                )
                cur.execute("CREATE INDEX IF NOT EXISTS ix_events ON events(module, kind, ts)")
        else:
            import sqlite3

            self.conn = sqlite3.connect(path, check_same_thread=False)
            self.conn.row_factory = sqlite3.Row
            self._ph = "?"
            self.conn.execute(
                """CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    module TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    ts REAL NOT NULL,
                    payload TEXT NOT NULL
                )"""
            )
            self.conn.execute("CREATE INDEX IF NOT EXISTS ix_events ON events(module, kind, ts)")
            self.conn.commit()

    def append(self, module: str, kind: str, payload: dict, ts: float | None = None) -> int:
        ts = ts if ts is not None else time.time()
        sql = (f"INSERT INTO events(module, kind, ts, payload) "
               f"VALUES ({self._ph},{self._ph},{self._ph},{self._ph})")
        if self.backend == "postgres":
            with self.conn.cursor() as cur:
                cur.execute(sql + " RETURNING id", (module, kind, ts, json.dumps(payload)))
                return cur.fetchone()[0]
        cur = self.conn.execute(sql, (module, kind, ts, json.dumps(payload)))
        self.conn.commit()
        return cur.lastrowid

    def query(self, module: str, kind: str | None = None, since: float | None = None,
              limit: int = 1000) -> list[dict]:
        sql = f"SELECT id, module, kind, ts, payload FROM events WHERE module={self._ph}"
        args: list = [module]
        if kind:
            sql += f" AND kind={self._ph}"
            args.append(kind)
        if since is not None:
            sql += f" AND ts>={self._ph}"
            args.append(since)
        sql += f" ORDER BY ts DESC LIMIT {self._ph}"
        args.append(limit)

        if self.backend == "postgres":
            with self.conn.cursor() as cur:
                cur.execute(sql, args)
                cols = [d[0] for d in cur.description]
                rows = [dict(zip(cols, r)) for r in cur.fetchall()]
        else:
            rows = [dict(r) for r in self.conn.execute(sql, args)]
        return [{**r, "payload": json.loads(r["payload"])} for r in rows]
