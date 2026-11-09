import sqlite3

from xassistant.database import ReminderStore


def test_create_persists_reminder_and_schedule_atomically(tmp_path):
    database = tmp_path / "reminders.sqlite3"
    store = ReminderStore(str(database))
    saved = store.create(
        request_id="request-1",
        transcript="Remind me to call the dentist tomorrow at 9",
        title="Call the dentist",
        scheduled_at="2026-11-09T09:00:00-06:00",
        timezone_name="America/Chicago",
        recurrence=None,
    )

    assert saved["id"] == 1
    assert saved["status"] == "active"
    with sqlite3.connect(database) as db:
        reminder_count = db.execute("SELECT COUNT(*) FROM reminders").fetchone()[0]
        schedule = db.execute(
            "SELECT reminder_id, due_at, state FROM reminder_schedule"
        ).fetchone()
    assert reminder_count == 1
    assert schedule == (1, "2026-11-09T09:00:00-06:00", "pending")


def test_same_request_id_is_idempotent(tmp_path):
    store = ReminderStore(str(tmp_path / "reminders.sqlite3"))
    arguments = dict(
        request_id="same-key",
        transcript="Remind me tomorrow at 9",
        title="Reminder",
        scheduled_at="2026-11-09T09:00:00-06:00",
        timezone_name="America/Chicago",
        recurrence=None,
    )
    first = store.create(**arguments)
    second = store.create(**arguments)
    assert first["id"] == second["id"]
    assert len(store.list()) == 1


def test_request_id_cannot_be_reused_for_different_transcript(tmp_path):
    store = ReminderStore(str(tmp_path / "reminders.sqlite3"))
    arguments = dict(
        request_id="same-key",
        transcript="First request",
        title="First",
        scheduled_at="2026-11-09T09:00:00-06:00",
        timezone_name="America/Chicago",
        recurrence=None,
    )
    store.create(**arguments)
    arguments["transcript"] = "Different request"
    with pytest.raises(ValueError, match="different transcript"):
        store.create(**arguments)


def test_cancel_updates_reminder_and_schedule(tmp_path):
    database = tmp_path / "reminders.sqlite3"
    store = ReminderStore(str(database))
    saved = store.create(
        request_id="cancel-me",
        transcript="Remind me tomorrow",
        title="Reminder",
        scheduled_at="2026-11-09T09:00:00-06:00",
        timezone_name="America/Chicago",
        recurrence=None,
    )
    cancelled = store.set_status(saved["id"], "cancelled")
    assert cancelled["status"] == "cancelled"
    with sqlite3.connect(database) as db:
        state = db.execute(
            "SELECT state FROM reminder_schedule WHERE reminder_id = ?", (saved["id"],)
        ).fetchone()[0]
    assert state == "cancelled"


import pytest
