import json
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx

from .config import Settings
from .schemas import ReminderInterpretation


SYSTEM_PROMPT = """You interpret a user's natural-language reminder request.
Understand the request yourself; do not rely on fixed phrase patterns.
Return one JSON object matching the requested fields.
Use the supplied current date, time, and timezone to resolve temporal language.
Do not invent a date, time, recurrence, or task detail that the user did not express.
If an essential detail is genuinely ambiguous or missing, use
intent="clarification_required" and ask one concise question.
If the text is not a reminder request, use intent="unsupported".
Do not claim that a reminder has been saved. You only interpret the text.
"""


class OllamaError(RuntimeError):
    pass


class OllamaInterpreter:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def interpret(self, transcript: str) -> ReminderInterpretation:
        now = datetime.now(ZoneInfo(self.settings.timezone))
        user_prompt = (
            f"Current local date and time: {now.isoformat()}\n"
            f"Timezone: {self.settings.timezone}\n\n"
            "Interpret this exact transcript. Preserve its intended meaning.\n"
            f"Transcript: {json.dumps(transcript, ensure_ascii=False)}\n\n"
            "Return JSON with these fields: intent, title, scheduled_at, timezone, "
            "recurrence, clarification_question. Use null for fields that do not apply. "
            "scheduled_at must be an ISO-8601 datetime with an explicit UTC offset."
        )

        payload = {
            "model": self.settings.ollama_model,
            "stream": False,
            "format": "json",
            "keep_alive": self.settings.ollama_keep_alive,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "options": {"temperature": 0, "num_predict": 256},
        }

        try:
            async with httpx.AsyncClient(timeout=self.settings.ollama_timeout_seconds) as client:
                response = await client.post(
                    f"{self.settings.ollama_url.rstrip('/')}/api/chat",
                    json=payload,
                )
                response.raise_for_status()
                body = response.json()
                content = body.get("message", {}).get("content", "")
                if not content:
                    raise OllamaError("Ollama returned an empty response.")
                return ReminderInterpretation.model_validate_json(content)
        except httpx.HTTPError as exc:
            raise OllamaError(f"Ollama request failed: {exc}") from exc
        except (ValueError, TypeError) as exc:
            raise OllamaError(f"Ollama returned invalid reminder JSON: {exc}") from exc
