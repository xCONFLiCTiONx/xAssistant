from contextlib import asynccontextmanager
from typing import AsyncIterator
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import FastAPI, HTTPException

from .config import get_settings
from .ollama import OllamaError, OllamaInterpreter
from .schemas import InterpretRequest, InterpretResponse


settings = get_settings()
interpreter = OllamaInterpreter(settings)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    try:
        ZoneInfo(settings.timezone)
    except ZoneInfoNotFoundError as exc:
        raise RuntimeError(f"Invalid configured timezone: {settings.timezone}") from exc
    yield


app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    description="Local reminder interpretation API. Stage 1 does not persist or schedule reminders.",
    lifespan=lifespan,
)


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

    if result.intent == "clarification_required":
        status = "clarification_required"
    elif result.intent == "unsupported":
        status = "rejected"
    else:
        status = "interpreted"

    return InterpretResponse(
        request_id=request.request_id,
        original_transcript=request.transcript,
        status=status,
        interpretation=result,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("xassistant.main:app", host=settings.host, port=settings.port)
