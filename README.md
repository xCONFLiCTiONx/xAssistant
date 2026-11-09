# xAssistant

A private, self-hosted reminder service powered by a small local language model. xAssistant accepts natural-language reminder requests from Android, a Samsung Galaxy Watch companion, and eventually Windows. The server interprets each request, independently checks the proposed reminder, stores it, and schedules it.

The goal is to make setting a reminder as natural as saying it—not to make users learn a rigid command syntax.

## Project goals

- Create reminders from natural language, including varied phrasing and complicated date/time expressions.
- Use Google-provided speech recognition on the device to turn speech into text. The AI service receives **text only**, never audio.
- Let the AI determine the intended reminder title, date, time, and recurrence.
- Independently validate the AI's proposed result against the original transcript before saving.
- Keep one authoritative reminder database and scheduler on xServer.
- Support Android phone, Galaxy Watch, and eventually Windows through the same server API and interpretation pipeline.
- Keep the system private, local-first, responsive, and suitable for an 8 GB RAM server.

## Architecture

All clients use the same xServer service. Clients are interfaces and transport endpoints; they are not separate reminder brains or authoritative schedulers.

```text
Galaxy Watch
  Google speech recognition
       |
       | recognized text over paired-device connection
       v
Android phone app
  - preserves the exact transcript
  - sends text to xServer
       |
       | local network / configured secure remote route
       v
xServer reminder API
  - supplies current local date, time, and timezone
  - asks the local AI to interpret the transcript
  - validates the structured result independently
  - commits the reminder to the database
  - creates/updates the server scheduling record
       |
       +--> success or failure response to the phone
       |
       +--> server-side reminder scheduling

Android phone app --------------------+
                                      |
Windows reminder app (future) --------+--> same xServer API and database
```

### Speech recognition and transcript handling

- The phone and watch use the available Google/Android speech-recognition experience for speech-to-text.
- Speech recognition happens on the device. The reminder AI is sent only the recognized text.
- The phone app can also accept a reminder spoken directly into the phone.
- Preserve the exact recognized transcript as the original request. Do not replace it with a rewritten or AI-generated version.
- If interpretation fails, is malformed, or is uncertain, retain and return the original transcript so the user can see what was actually recognized.
- The guarantee is preservation of the recognized text—not a guarantee that speech recognition always transcribes the spoken words perfectly.
- Third-party applications must use supported Android speech-recognition APIs/providers. They must not assume access to Google's private internal personalization model or undocumented services.
- The watch-to-phone speech-recognition route must be verified during implementation. Depending on supported Wear OS/Samsung APIs and device behavior, recognition may happen on the watch or be initiated through the phone. The watch must not require its own internet connection for the intended paired-phone workflow.

### AI interpretation

The AI's job is to understand the user's original text and return structured reminder data.

It should be a semantic interpreter—not a phrase-matching rules engine, a tool-using agent, or a rigid command parser. Do not bury the model under so many phrase-specific instructions that ordinary application code is effectively doing the interpretation instead.

The model should receive:
- The exact original recognized transcript.
- The current date and time.
- The relevant local timezone (initially the server's configured local timezone, currently expected to be `America/Chicago`).
- A concise task definition and a structured output contract.

The model should determine the intended:
- Reminder title.
- Date and time.
- Recurrence, when requested.
- Whether the request is sufficiently clear to create a reminder or needs clarification.

The output should be concise, machine-readable structured data (JSON). The schema is a data contract, not a tool interface. The model does not need database access, operating-system access, application APIs, or permission to execute actions.

An illustrative response shape (not a final schema):

```json
{
  "intent": "create_reminder",
  "title": "Go to work",
  "scheduled_at": "2026-11-09T08:00:00-06:00",
  "timezone": "America/Chicago",
  "recurrence": null,
  "requires_clarification": false
}
```

The final schema must be designed and tested against real requests, including relative dates, conversational phrasing, recurring reminders, and ambiguous requests.

### Independent verification and commit

The server—not the model—owns validation, persistence, and scheduling.

Before committing a reminder, the server must:
1. Validate the response structure and required fields.
2. Validate date/time values, timezone, recurrence representation, and supported scheduling limits.
3. Check that the proposed interpretation is supported by the original transcript and supplied time context. This check is a safety net against hallucinated or invented details, not a second phrase-based natural-language interpreter.
4. Reject unsupported, contradictory, malformed, or ambiguous results rather than guessing.
5. Use an idempotency strategy so retries do not create duplicate reminders.
6. Commit the reminder to the database and create its scheduling record.
7. Return success only after the commit and scheduling operation have succeeded.

The model's own assertion that its result is correct is not independent verification. A second model may be evaluated later, but it must not be treated as a substitute for deterministic validation and carefully designed tests.

If the request cannot be interpreted safely, the service should ask for clarification or return a clear failure with the original transcript. It must never silently discard a request or report success for a reminder that was not saved.

### Reminder storage and scheduling

- xServer is the single source of truth for reminder records and server-side scheduling.
- The database must persist reminders and the information needed to schedule, update, cancel, and recover them after service restarts.
- Database commit and scheduler registration must be handled as one reliable workflow. The API must not report success if either required step fails.
- Operations should be idempotent so clients can safely retry after network failures.
- The API should provide explicit success and failure results, with enough information for clients to display the outcome and refresh their local state.
- Reminder edits, cancellations, and completion state must flow through the same authoritative service.

## Client applications

### Android phone app

The phone is the primary mobile client and the bridge between the watch and xServer.

Expected responsibilities:
- Capture speech using the device's supported Google/Android speech-recognition provider, or accept typed text.
- Send only the recognized text to xServer.
- Preserve and display the exact original transcript.
- Display the interpreted reminder details and the server's definitive result.
- Provide a reminder list and controls to view, edit, and cancel reminders.
- Maintain a local cache for viewing reminders while offline.
- Queue supported offline edits for later synchronization, clearly showing that they are pending and not yet confirmed by the server.
- Synchronize on app use, on demand, or at appropriate discrete moments—not through a permanent connection or continuous polling.
- Display a clear success indicator (green check) only after server confirmation.
- Display a clear failure indicator (red alert) when the server rejects or cannot save a request.

The phone may maintain local notification backups for reminders already synchronized to it. Such backups improve resilience but do not make the phone authoritative. Android notification delivery, exact-alarm permissions, battery optimization, force-stop behavior, and device availability impose platform limitations; the project must communicate and handle those limitations honestly.

### Samsung Galaxy Watch companion

The watch is a lightweight companion, not an independent server client or scheduler.

Expected workflow:
1. The user opens the watch companion and speaks a reminder.
2. Supported speech recognition produces text.
3. The watch sends the recognized text to the paired phone using the supported Samsung/Wear OS paired-device communication mechanism (for example, the Wear OS Data Layer where supported).
4. The phone forwards the text to xServer.
5. The phone relays the definitive success or failure result back to the watch.

Requirements:
- The watch must not need an internet connection for this paired-phone workflow.
- The watch sends text, not audio, to the phone and server.
- The watch should show a clear result, including the reminder title on success and the original transcript on failure where practical.
- Handle a disconnected phone or unavailable paired-device link without pretending the reminder was saved.
- Verify the actual target Galaxy Watch model, OS version, and supported APIs before choosing implementation details.

### Windows reminder app (future client)

A Windows client is planned for a later phase.

- It must use the same xServer API, interpretation pipeline, database, and scheduler as Android.
- It must not introduce a separate AI prompt or independent reminder interpretation engine.
- It should provide the same authoritative success/failure behavior and reminder management capabilities.
- Its implementation should be planned so the server API is reusable across all clients.

## Model and server constraints

Initial server environment:
- Dell OptiPlex 3070 Micro.
- Intel Core i5-9500, 6 cores.
- 8 GB RAM; keeping the machine at 8 GB is a strong preference.
- No useful GPU; inference runs on CPU.
- Ubuntu Server/Desktop environment.
- Ollama is the local model runtime.
- xServer LAN address: `192.168.88.50`.
- Ollama API currently listens on `192.168.88.50:11434`.

Model selection priorities:
1. Small memory footprint and fast CPU inference.
2. Reliable natural-language understanding of reminder intent and temporal expressions.
3. Consistent structured JSON output.
4. Good handling of ambiguous or difficult requests.
5. Practical performance on the existing 8 GB server.

The existing Qwen 3.5 2B model is a baseline, not a mandatory final choice. Smaller models may be evaluated if they provide better speed while meeting accuracy requirements. Do not assume a larger model is the answer. Do not download, replace, or remove models as part of design work without an explicit decision.

Ollama and model compatibility must be verified against the actual installed Ollama version before implementation relies on newer API features such as schema-constrained structured output.

### Model loading and wake-up optimization

The watch companion should be able to trigger a lightweight wake signal through the phone when the user opens the watch app. The phone can then send a one-shot preload request to xServer so the reminder model has an opportunity to load while the user is speaking.

Requirements:
- Wake/preload is a lightweight request, not a persistent connection.
- Debounce repeated app-open signals.
- Do not wake the phone screen or require the phone to be unlocked.
- If the phone's configured VPN/secure route is not ready, use a bounded one-shot retry with backoff.
- Do not use continuous polling.
- Keep the model resident for a short, configurable period after use, then allow it to unload to conserve RAM.
- Verify the installed Ollama version's supported preload and `keep_alive` behavior before implementation.
- Do not download models or change the current model inventory as part of this optimization without explicit approval.

## Reliability, privacy, and security

- Keep reminder processing local to xServer wherever possible.
- Do not transmit voice recordings to xServer; send recognized text only.
- Protect client-to-server communication and authenticate clients.
- Do not expose the Ollama API directly to untrusted networks. Put client access behind the reminder API and its authentication/authorization layer.
- Treat client requests and model output as untrusted input.
- Validate all fields on the server before database writes.
- Use request identifiers/idempotency keys for safe retries.
- Make failures visible. Never silently drop a request.
- Do not claim a reminder is saved until persistence and scheduling have both succeeded.
- Preserve enough request/result state for troubleshooting without unnecessarily retaining sensitive content.
- Define retention and deletion behavior for original transcripts and reminder records before release.

## Testing strategy

Testing must emphasize semantic accuracy and failure handling, not just whether the model returns valid JSON.

Build a representative corpus of real-world reminder requests, including:
- Direct requests with explicit dates and times.
- Relative dates and times (for example, later today, tomorrow morning, next week).
- Different natural phrasings for the same intent.
- Requests with date but no time, or time but no date.
- Recurring reminders and recurrence changes.
- Weekday, weekend, month-boundary, year-boundary, and daylight-saving transitions.
- Requests made near midnight.
- Ambiguous, contradictory, incomplete, or unsupported requests.
- Difficult conversational wording that may confuse a small model.
- Inputs where a plausible-sounding model response invents a time, date, title detail, or recurrence.

For every test, evaluate:
- Whether the model understood the intended reminder.
- Correctness of title, date, time, timezone, and recurrence.
- Whether ambiguity was correctly identified instead of guessed.
- Whether server verification accepted valid results and rejected unsupported ones.
- Whether the exact original transcript was preserved.
- Whether retries, timeouts, malformed responses, server restarts, and database/scheduler failures avoid false success and duplicate reminders.

Measure inference latency and memory use on the actual xServer hardware. Do not treat general model benchmarks as proof that a model is reliable for this project.

## Development principles

- Keep the AI responsible for semantic interpretation; keep application code responsible for validation, persistence, scheduling, transport, and user-interface behavior.
- Prefer a concise prompt and a clear structured output contract over a large collection of phrase-specific rules.
- Do not turn the reminder interpreter into a rigid natural-language parser.
- Do not give the model tools or direct access to the database, host, or client applications.
- Use one shared server-side interpretation pipeline for every client.
- Keep the architecture modular and testable.
- Avoid unrelated changes to existing xServer services or repositories.
- Do not add GitHub Actions workflows unless explicitly requested.
- Do not make destructive configuration, repository, or model changes without explicit approval.

## Initial implementation sequence

1. Establish this README as the project requirements and architecture baseline.
2. Inspect the target xServer environment and confirm the actual Ollama API/runtime capabilities without changing existing services.
3. Define the reminder API contract, response states, idempotency behavior, and database/scheduler transaction strategy.
4. Build a reminder interpretation test corpus and benchmark the existing small model plus carefully selected smaller candidates.
5. Implement and test the server-side interpretation, independent validation, persistence, and scheduling pipeline.
6. Build the Android phone client.
7. Build and validate the Galaxy Watch companion and paired-device communication.
8. Add the Windows client using the same server API.
9. Test offline behavior, reconnection, retries, notification limitations, and end-to-end reliability.

---

This README records the agreed project direction. Implementation details that depend on the actual device, OS, runtime, or test results must be verified before they are treated as final.
