from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .schemas import ReminderInterpretation


class ReminderValidationError(ValueError):
    pass


def validate_interpretation(result: ReminderInterpretation, configured_timezone: str) -> tuple[str, str]:
    if result.intent != "create_reminder":
        raise ReminderValidationError("The AI did not produce a reminder ready to save.")
    if not result.title or not result.title.strip():
        raise ReminderValidationError("The AI did not provide a reminder title.")
    if result.scheduled_at is None or result.scheduled_at.tzinfo is None:
        raise ReminderValidationError("The proposed date and time must include a UTC offset.")
    if result.timezone != configured_timezone:
        raise ReminderValidationError("The proposed timezone does not match the server's configured timezone.")
    try:
        zone = ZoneInfo(configured_timezone)
    except ZoneInfoNotFoundError as exc:
        raise ReminderValidationError("The configured server timezone is invalid.") from exc

    proposed = result.scheduled_at
    local = proposed.astimezone(zone)
    if local.utcoffset() != proposed.utcoffset():
        raise ReminderValidationError("The proposed UTC offset conflicts with the configured timezone.")
    if proposed.astimezone(timezone.utc) <= datetime.now(timezone.utc):
        raise ReminderValidationError("The proposed reminder time is not in the future.")

    return proposed.isoformat(), configured_timezone
