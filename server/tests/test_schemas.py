from datetime import datetime

import pytest
from pydantic import ValidationError

from xassistant.schemas import InterpretRequest, ReminderInterpretation


def test_request_preserves_transcript_exactly():
    transcript = "Remind me   tomorrow at 8 to call Mom."
    request = InterpretRequest(transcript=transcript, request_id="req-1")
    assert request.transcript == transcript


def test_interpretation_accepts_offset_aware_datetime():
    result = ReminderInterpretation(
        intent="create_reminder",
        title="Call Mom",
        scheduled_at="2026-11-09T08:00:00-06:00",
        timezone="America/Chicago",
    )
    assert result.scheduled_at.utcoffset() is not None


def test_interpretation_rejects_unexpected_fields():
    with pytest.raises(ValidationError):
        ReminderInterpretation.model_validate(
            {"intent": "create_reminder", "unexpected": "do not accept"}
        )


def test_request_rejects_empty_transcript():
    with pytest.raises(ValidationError):
        InterpretRequest(transcript="   ", request_id="req-2")
