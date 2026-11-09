from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from xassistant.schemas import ReminderInterpretation
from xassistant.validation import ReminderValidationError, validate_interpretation


def ready(at: datetime) -> ReminderInterpretation:
    return ReminderInterpretation(
        intent="create_reminder",
        title="Pick up groceries",
        scheduled_at=at,
        timezone="America/Chicago",
    )


def test_accepts_future_local_datetime():
    future = datetime.now(ZoneInfo("America/Chicago")) + timedelta(hours=2)
    value, zone = validate_interpretation(ready(future), "America/Chicago")
    assert value == future.isoformat()
    assert zone == "America/Chicago"


def test_rejects_naive_datetime():
    future = datetime.now() + timedelta(hours=2)
    with pytest.raises(ReminderValidationError, match="UTC offset"):
        validate_interpretation(ready(future), "America/Chicago")


def test_rejects_wrong_timezone():
    future = datetime.now(ZoneInfo("America/New_York")) + timedelta(hours=2)
    with pytest.raises(ReminderValidationError, match="timezone"):
        validate_interpretation(ready(future), "America/Chicago")


def test_rejects_past_datetime():
    past = datetime.now(ZoneInfo("America/Chicago")) - timedelta(hours=1)
    with pytest.raises(ReminderValidationError, match="future"):
        validate_interpretation(ready(past), "America/Chicago")


def test_rejects_missing_title():
    future = datetime.now(ZoneInfo("America/Chicago")) + timedelta(hours=2)
    result = ready(future).model_copy(update={"title": None})
    with pytest.raises(ReminderValidationError, match="title"):
        validate_interpretation(result, "America/Chicago")
