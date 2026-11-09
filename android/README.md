# xAssistant Android

Native Kotlin / Jetpack Compose phone client.

## Implemented

- Device speech recognition and typed reminder entry; audio is not uploaded.
- Durable reminder-save requests through WorkManager. A save already queued continues when the app is backgrounded or its process is reclaimed, and waits for network connectivity instead of losing the request.
- Server-backed reminder list, completion, and cancellation.
- Local Android notifications scheduled with AlarmManager after the server confirms the reminder was saved.
- Exact alarms during Doze when the user grants Android's exact-alarm access.
- Notification delivery without a persistent service or polling loop; the app remains idle between reminder events.
- Alarm restoration after device reboot and exact-alarm permission changes.
- Suppresses the reminder notification while xAssistant is in the foreground.
- Displays scheduled times converted to the phone's local timezone.
- Only displays the original transcript and server error when a save fails or needs clarification.

## Notification setup

On first launch, allow notifications. Also grant **Allow exact alarms** in the app's prompt/settings link. Without notification permission, Android cannot show reminders. Without exact-alarm access, Android falls back to inexact alarms, which may be delayed by Doze.

Android preserves scheduled alarms when the app process is killed or swiped away. No always-running foreground service is used. Android force-stop, uninstall, or user-revoked permissions can still prevent delivery; Android does not permit an app to bypass those controls.

## Build

Open this `android/` directory in Android Studio with JDK 17 and Android SDK 35.

The development server URL defaults to `http://192.168.88.50:5081`. Override it by adding this to `android/gradle.properties`:

```properties
xassistantServerUrl=http://YOUR_XSERVER_ADDRESS:5081
```

The current development endpoint uses cleartext HTTP and is intended for the trusted LAN only. Do not expose it publicly.

## Current delivery boundary

The phone schedules notifications locally for reminders returned by xServer when the app syncs. This means reminders saved on the phone still fire without internet at the due time, and Wear OS can mirror phone notifications to a paired watch.

The server currently persists reminders but does not run a durable delivery scheduler or send FCM push notifications to registered devices. Reminders created by another client while the phone is not syncing are therefore not guaranteed to reach this phone yet. Server-side scheduling/push and device registration are the next backend requirement for reliable cross-device delivery. Recurring reminders also need scheduler expansion beyond their first occurrence.

## Next phase

- Galaxy Watch companion and phone/watch Data Layer synchronization.
- Server scheduler + FCM device registration for reminders created from Windows, watch, or another client.
- HTTPS and authentication before remote Zero Trust access.
