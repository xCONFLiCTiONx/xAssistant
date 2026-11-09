import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class ReminderStore:
    def __init__(self, database_path: str):
        self.database_path = database_path
        Path(database_path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    def initialize(self) -> None:
        with self.connect() as db:
            db.execute("PRAGMA journal_mode = WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS reminders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT NOT NULL UNIQUE,
                    original_transcript TEXT NOT NULL,
                    title TEXT NOT NULL,
                    scheduled_at TEXT NOT NULL,
                    timezone TEXT NOT NULL,
                    recurrence TEXT,
                    status TEXT NOT NULL DEFAULT 'active'
                        CHECK(status IN ('active', 'completed', 'cancelled')),
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS ix_reminders_schedule
                    ON reminders(status, scheduled_at);
                CREATE TABLE IF NOT EXISTS reminder_schedule (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reminder_id INTEGER NOT NULL UNIQUE REFERENCES reminders(id) ON DELETE CASCADE,
                    due_at TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'pending'
                        CHECK(state IN ('pending', 'claimed', 'delivered', 'cancelled')),
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS ix_schedule_due
                    ON reminder_schedule(state, due_at);
            """)

    @staticmethod
    def _row(row: sqlite3.Row) -> dict[str, Any]:
        return dict(row)

    def create(self, request_id: str, transcript: str, title: str,
               scheduled_at: str, timezone_name: str, recurrence: str | None) -> dict[str, Any]:
        now = datetime.now(timezone.utc).isoformat()
        with self.connect() as db:
            existing = db.execute(
                "SELECT * FROM reminders WHERE request_id = ?", (request_id,)
            ).fetchone()
            if existing:
                return self._row(existing)
            cursor = db.execute("""
                INSERT INTO reminders
                    (request_id, original_transcript, title, scheduled_at, timezone, recurrence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (request_id, transcript, title, scheduled_at, timezone_name, recurrence, now))
            reminder_id = cursor.lastrowid
            db.execute("""
                INSERT INTO reminder_schedule (reminder_id, due_at, created_at)
                VALUES (?, ?, ?)
            """, (reminder_id, scheduled_at, now))
            row = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            return self._row(row)

    def list(self, include_inactive: bool = False) -> list[dict[str, Any]]:
        with self.connect() as db:
            query = "SELECT * FROM reminders"
            if not include_inactive:
                query += " WHERE status = 'active'"
            query += " ORDER BY scheduled_at, id"
            return [self._row(row) for row in db.execute(query).fetchall()]

    def get(self, reminder_id: int) -> dict[str, Any] | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            return self._row(row) if row else None

    def update(self, reminder_id: int, title: str, scheduled_at: str,
               timezone_name: str, recurrence: str | None) -> dict[str, Any] | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            if not row or row["status"] != "active":
                return None
            db.execute("""
                UPDATE reminders SET title=?, scheduled_at=?, timezone=?, recurrence=?
                WHERE id=?
            """, (title, scheduled_at, timezone_name, recurrence, reminder_id))
            db.execute("""
                UPDATE reminder_schedule SET due_at=?, state='pending'
                WHERE reminder_id=?
            """, (scheduled_at, reminder_id))
            updated = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            return self._row(updated)

    def set_status(self, reminder_id: int, status: str) -> dict[str, Any] | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            if not row:
                return None
            db.execute("UPDATE reminders SET status=? WHERE id=?", (status, reminder_id))
            schedule_state = "cancelled" if status in ("cancelled", "completed") else "pending"
            db.execute("UPDATE reminder_schedule SET state=? WHERE reminder_id=?",
                       (schedule_state, reminder_id))
            updated = db.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            return self._row(updated)
