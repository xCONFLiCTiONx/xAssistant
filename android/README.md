# xAssistant Android

Native Kotlin / Jetpack Compose phone client.

## Implemented

- Uses Android `RecognizerIntent` for the device's configured speech-recognition provider (Google Speech Services when selected on the device).
- Accepts typed reminder text as well as recognized speech.
- Keeps the recognized transcript visible for review and sends only that text to xServer; no audio is uploaded.
- Calls `POST /v1/reminders/interpret` and displays the original transcript plus the structured interpretation, clarification, or failure.
- Does not claim a reminder was saved. The current server endpoint interprets requests only; database persistence and scheduling are still outstanding.

## Build

Open this `android/` directory in Android Studio with JDK 17 and Android SDK 35. The project uses Android Gradle Plugin 8.9.2 and Kotlin 2.1.10.

The development server URL defaults to `http://192.168.88.50:5081`. Override it by adding this to `android/gradle.properties`:

```properties
xassistantServerUrl=http://YOUR_XSERVER_ADDRESS:5081
```

The current backend defaults to loopback binding (`127.0.0.1`), so it is not yet reachable from a phone. Configure a secured LAN deployment before device testing; do not expose this cleartext development endpoint to the public internet.

## Remaining Android work

- Server-backed reminder list, edit, cancel, and completion screens once the CRUD API exists.
- Local read cache and explicit queued/pending states for offline changes.
- Show green success only after the server confirms both database commit and scheduling.
- Authentication and HTTPS for remote access.
- Galaxy Watch paired-device bridge (separate companion module / phase).
