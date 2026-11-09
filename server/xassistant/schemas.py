from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class InterpretRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    transcript: str = Field(min_length=1, max_length=4000)
    request_id: str = Field(min_length=1, max_length=128)


class ReminderInterpretation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    intent: Literal["create_reminder", "clarification_required", "unsupported"]
    title: str | None = Field(default=None, max_length=240)
    scheduled_at: datetime | None = None
    timezone: str | None = None
    recurrence: str | None = Field(default=None, max_length=500)
    clarification_question: str | None = Field(default=None, max_length=500)


class InterpretResponse(BaseModel):
    request_id: str
    original_transcript: str
    status: Literal["interpreted", "clarification_required", "rejected"]
    interpretation: ReminderInterpretation | None = None
    error: str | None = None


class ReminderMutation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=240)
    scheduled_at: datetime
    timezone: str = Field(min_length=1, max_length=80)
    recurrence: str | None = Field(default=None, max_length=500)


class ReminderRecord(ReminderMutation):
    id: int
    request_id: str
    original_transcript: str
    status: Literal["active", "completed", "cancelled"]
    created_at: datetime


class ReminderSaveResponse(BaseModel):
    request_id: str
    original_transcript: str
    status: Literal["saved", "clarification_required", "rejected"]
    reminder: ReminderRecord | None = None
    interpretation: ReminderInterpretation | None = None
    error: str | None = None
