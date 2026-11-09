from contextlib import asynccontextmanager
from datetime import datetime
from typing import AsyncIterator
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import FastAPI, HTTPException, Query

from .config import get_settings
from .database import ReminderStore
from .ollama import OllamaError, OllamaInterpreter
from .schemas import (
    InterpretRequest, InterpretResponse, ReminderInterpretation,
    ReminderMutation, ReminderRecord, ReminderSaveResponse,
)
from .validation import ReminderValidationError, validate_interpretation


settings = get_settings()
interpreter = OllamaInterpreter(settings)
store = ReminderStore(settings.database_path)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    try:
        ZoneInfo(settings.timezone)
    except ZoneInfoNotFoundError as exc:
        raise RuntimeError(f"Invalid configured timezone: {settings.timezone}") from exc
    yield


app = FastAPI(
    title=settings.app_name,
    version="0.2.0",
    description="Local reminder interpretation API with persistent SQLite storage.",
    lifespan=lifespan,
)


def record(value: dict) -> ReminderRecord:
    return ReminderRecord.model_validate(value)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "xassistant-server"}


@app.post("/v1/reminders/interpret", response_model=InterpretResponse)
async def interpret_reminder(request: InterpretRequest) -> InterpretResponse:
    try:
        result = await interpreter.interpret(request.transcript)
    except OllamaError as exc:
        return InterpretResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="rejected",
            error=str(exc),
        )

    status = (
        "clarification_required" if result.intent == "clarification_required"
        else "rejected" if result.intent == "unsupported"
        else "interpreted"
    )
    return InterpretResponse(
        request_id=request.request_id,
        original_transcript=request.transcript,
        status=status,
        interpretation=result,
    )


@app.post("/v1/reminders", response_model=ReminderSaveResponse)
async def create_reminder(request: InterpretRequest) -> ReminderSaveResponse:
    try:
        result = await interpreter.interpret(request.transcript)
    except OllamaError as exc:
        return ReminderSaveResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="rejected",
            error=str(exc),
        )

    if result.intent == "clarification_required":
        return ReminderSaveResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="clarification_required",
            interpretation=result,
        )
    if result.intent != "create_reminder":
        return ReminderSaveResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="rejected",
            interpretation=result,
            error="The text was not interpreted as a supported reminder request.",
        )

    try:
        scheduled_at, timezone_name = validate_interpretation(result, settings.timezone)
        saved = store.create(
            request_id=request.request_id,
            transcript=request.transcript,
            title=result.title.strip(),
            scheduled_at=scheduled_at,
            timezone_name=timezone_name,
            recurrence=result.recurrence,
        )
    except ReminderValidationError as exc:
        return ReminderSaveResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="rejected",
            interpretation=result,
            error=str(exc),
        )
    except (ValueError, RuntimeError) as exc:
        return ReminderSaveResponse(
            request_id=request.request_id,
            original_transcript=request.transcript,
            status="rejected",
            interpretation=result,
            error=f"Reminder could not be committed: {exc}",
        )

    return ReminderSaveResponse(
        request_id=request.request_id,
        original_transcript=request.transcript,
        status="saved",
        reminder=record(saved),
        interpretation=result,
    )


@app.get("/v1/reminders", response_model=list[ReminderRecord])
async def list_reminders(include_inactive: bool = Query(default=False)) -> list[ReminderRecord]:
    return [record(item) for item in store.list(include_inactive)]


@app.get("/v1/reminders/{reminder_id}", response_model=ReminderRecord)
async def get_reminder(reminder_id: int) -> ReminderRecord:
    item = store.get(reminder_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Reminder not found.")
    return record(item)


@app.put("/v1/reminders/{reminder_id}", response_model=ReminderRecord)
async def update_reminder(reminder_id: int, update: ReminderMutation) -> ReminderRecord:
    proposed = ReminderInterpretation(
        intent="create_reminder",
        title=update.title,
        scheduled_at=update.scheduled_at,
        timezone=update.timezone,
        recurrence=update.recurrence,
    )
    try:
        scheduled_at, timezone_name = validate_interpretation(proposed, settings.timezone)
    except ReminderValidationError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    updated = store.update(
        reminder_id, update.title.strip(), scheduled_at, timezone_name, update.recurrence
    )
    if updated is None:
        raise HTTPException(status_code=404, detail="Active reminder not found.")
    return record(updated)


@app.post("/v1/reminders/{reminder_id}/complete", response_model=ReminderRecord)
async def complete_reminder(reminder_id: int) -> ReminderRecord:
    updated = store.set_status(reminder_id, "completed")
    if updated is None:
        raise HTTPException(status_code=404, detail="Reminder not found.")
    return record(updated)


@app.delete("/v1/reminders/{reminder_id}", response_model=ReminderRecord)
async def cancel_reminder(reminder_id: int) -> ReminderRecord:
    updated = store.set_status(reminder_id, "cancelled")
    if updated is None:
        raise HTTPException(status_code=404, detail="Reminder not found.")
    return record(updated)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("xassistant.main:app", host=settings.host, port=settings.port)
