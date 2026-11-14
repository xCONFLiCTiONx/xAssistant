import json
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx

from .config import Settings
from .schemas import ReminderInterpretation


SYSTEM_PROMPT = """Interpret the user's reminder request and return exactly one JSON object.
Use only the supplied current local date, time, and timezone to resolve dates.
Never invent missing details. If essential information is ambiguous, use
"clarification_required" and ask one concise question.
If the request is not a reminder, use "unsupported".
The intent MUST be exactly one of:
"create_reminder", "clarification_required", "unsupported".
Use "create_reminder" when the reminder has sufficient information.
Return these keys: intent, title, scheduled_at, timezone, recurrence,
clarification_question. Use null for inapplicable values.
scheduled_at must be ISO-8601 with an explicit UTC offset.
Return JSON only. Do not claim the reminder was saved.
"""


class OllamaError(RuntimeError):
    pass


class OllamaInterpreter:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def interpret(self, transcript: str) -> ReminderInterpretation:
        timezone_name = self.settings.timezone
        now = datetime.now(ZoneInfo(timezone_name))

        user_prompt = (
            f"Current local datetime: {now.isoformat()}\n"
            f"Timezone: {timezone_name}\n"
            f"Reminder request: {json.dumps(transcript, ensure_ascii=False)}"
        )

        payload = {
            "model": self.settings.ollama_model,
            "stream": False,
            "think": False,
            "format": "json",
            "keep_alive": self.settings.ollama_keep_alive,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "options": {
                "temperature": 0,
                "num_predict": 256,
            },
        }

        try:
            async with httpx.AsyncClient(
                timeout=self.settings.ollama_timeout_seconds
            ) as client:
                response = await client.post(
                    f"{self.settings.ollama_url.rstrip('/')}/api/chat",
                    json=payload,
                )
                response.raise_for_status()
                body = response.json()

                message = body.get("message") or {}
                content = message.get("content") or ""

                if not content.strip():
                    reason = body.get("done_reason", "unknown")
                    tokens = body.get("eval_count", "unknown")
                    raise OllamaError(
                        "Ollama returned no message content "
                        f"(done_reason={reason}, eval_count={tokens})."
                    )

                return ReminderInterpretation.model_validate_json(content)

        except httpx.HTTPError as exc:
            raise OllamaError(f"Ollama request failed: {exc}") from exc
        except (ValueError, TypeError) as exc:
            raise OllamaError(f"Ollama returned invalid reminder JSON: {exc}") from exc
