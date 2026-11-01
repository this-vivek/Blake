"""Append-only event store. Modules write events; views are derived by querying."""
import json
import sqlite3
import time


class Db:
    def __init__(self, path: str = "blake.db"):
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
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
        cur = self.conn.execute(
            "INSERT INTO events(module, kind, ts, payload) VALUES (?,?,?,?)",
            (module, kind, ts if ts is not None else time.time(), json.dumps(payload)),
        )
        self.conn.commit()
        return cur.lastrowid

    def query(self, module: str, kind: str | None = None, since: float | None = None,
              limit: int = 1000) -> list[dict]:
        sql = "SELECT id, module, kind, ts, payload FROM events WHERE module=?"
        args: list = [module]
        if kind:
            sql += " AND kind=?"
            args.append(kind)
        if since is not None:
            sql += " AND ts>=?"
            args.append(since)
        sql += " ORDER BY ts DESC LIMIT ?"
        args.append(limit)
        return [
            {**dict(r), "payload": json.loads(r["payload"])}
            for r in self.conn.execute(sql, args)
        ]
