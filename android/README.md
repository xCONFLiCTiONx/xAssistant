# xAssistant Android

Native Kotlin / Jetpack Compose phone client.

## Implemented

- Device speech recognition and typed reminder entry; audio is not uploaded.
- Durable reminder-save requests through WorkManager. A save already queued continues when the app is backgrounded or its process is reclaimed, and waits for network connectivity instead of losing the request.
- Server-backed reminder list, completion, and cancellation.
- Immediate "Reminder saved" notification after the server confirms a successful save, including when the app is backgrounded.
- Local Android notifications scheduled with AlarmManager after the server confirms the reminder was saved.
- In-app link to Android's battery-optimization exemption screen, plus notification and exact-alarm permission prompts.
- Exact alarms during Doze when the user grants Android's exact-alarm access.
- Notification delivery without a persistent service or polling loop; the app remains idle between reminder events.
- Alarm restoration after device reboot and exact-alarm permission changes.
- Suppresses the reminder notification while xAssistant is in the foreground.
- Displays scheduled times converted to the phone's local timezone.
- Only displays the original transcript and server error when a save fails or needs clarification.

## Notification setup

On first launch, allow notifications. Also grant **Allow exact alarms** in the app's prompt/settings link. Use **Disable battery optimization** in the app to open Android's system exemption prompt for xAssistant. This is optional but recommended for the most reliable background work on heavily customized Android builds. Without notification permission, Android cannot show reminders. Without exact-alarm access, Android falls back to inexact alarms, which may be delayed by Doze.

Android preserves scheduled alarms when the app process is killed or swiped away. No always-running foreground service is used. Android force-stop, uninstall, or user-revoked permissions can still prevent delivery; Android does not permit an app to bypass those controls.

## Build

Open this `android/` directory in Android Studio with JDK 17 and Android SDK 35.

The development server URL defaults to `http://192.168.88.50:5081`. Override it by adding this to `android/gradle.properties`:

```properties
xassistantServerUrl=http://YOUR_XSERVER_ADDRESS:5081
```

The current development endpoint uses cleartext HTTP and is intended for the trusted LAN only. Do not expose it publicly.

## Current delivery boundary

The phone stores a local alarm record (reminder ID, title, and scheduled timestamp) in app-private SharedPreferences and registers the alarm with Android AlarmManager. This small local cache lets an already-synced reminder fire without internet and survive ordinary process termination/reboot. The full reminder list and authoritative reminder data remain on xServer; the app does not currently maintain a complete offline database. The phone schedules notifications locally for reminders returned by xServer when the app syncs. This means reminders saved on the phone still fire without internet at the due time, and Wear OS can mirror phone notifications to a paired watch.

The server currently persists reminders but does not run a durable delivery scheduler or send FCM push notifications to registered devices. Reminders created by another client while the phone is not syncing are therefore not guaranteed to reach this phone yet. Server-side scheduling/push and device registration are the next backend requirement for reliable cross-device delivery. Recurring reminders also need scheduler expansion beyond their first occurrence.

## Next phase

- Galaxy Watch companion and phone/watch Data Layer synchronization.
- Server scheduler + FCM device registration for reminders created from Windows, watch, or another client.
- HTTPS and authentication before remote Zero Trust access.


## Wear OS companion

The `:wear` Gradle module builds the paired Galaxy Watch app. Install both phone and watch variants. The watch uses Android speech recognition to produce a text transcript, then sends that text to the paired phone over the Wear OS Data Layer. The phone forwards the transcript to the same xServer reminder API and relays the server result. The watch app does not call xServer directly and does not transmit audio.

- Phone app ID: `cc.xconflictionx.xassistant`
- Watch app ID: `cc.xconflictionx.xassistant.wear`
- Watch minimum SDK: 30 (Wear OS 3 generation and newer)
- Data Layer paths: `/xassistant/reminder` and `/xassistant/result`
- Build both modules from the `android/` project in Android Studio; select the phone or wear run configuration.

The watch launcher has a compact companion mark; the phone uses an adaptive launcher icon with matching colors. The phone remains the bridge and the xServer remains authoritative.
