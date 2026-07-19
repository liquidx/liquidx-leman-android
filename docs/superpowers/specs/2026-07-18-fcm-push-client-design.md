# FCM push notifications: Android client

**Date:** 2026-07-18
**Status:** Approved
**Scope:** The **client** half only. The notification server that connects to Hermes and
emits pushes is a separate project (separate spec). This spec defines the contract the client
assumes of that server.

## Problem

The app only learns about new messages while foregrounded (30s `SyncScheduler` loop). Anything
that lands while the app is backgrounded or closed — an agent finishing a long run, a `cron`
session, another client posting — is invisible until the user next opens the app. There is no
notification infrastructure of any kind today (no FCM, no `NotificationManager`, no WorkManager;
only `INTERNET` + `USE_BIOMETRIC` in the manifest). The spec deferral in
`docs/spec/01-architecture.md:122` ("push … needs gateway push support") is what we're now lifting.

## Decisions

| Question | Decision |
|---|---|
| Push role | **Signal-to-sync.** FCM push is a lightweight data ping; the client wakes, pulls fresh data from the gateway via the existing `SessionSyncer`, diffs Room, then posts a **local** notification. Gateway stays the single source of truth. |
| Firebase project | User creates it (guided, click-by-click); Claude writes all code. **Both** `net.liquidx.leman` and `net.liquidx.leman.debug` must be registered as Android apps. |
| Token registration | **Define the contract now, resilient no-op.** Client POSTs its token to `{serverUrl}/api/devices`; a 404 / unset URL logs and retries — never crashes, no user-facing error. Works unchanged once the server ships. |
| v1 filtering | **Notify on everything**, one global on/off toggle. Skip the user's own turns and system/`[IMPORTANT:` turns. Per-thread mute deferred. |
| Background execution | **WorkManager** (expedited `CoroutineWorker`) for both the sync-and-notify and the token registration, for reliability + retry. |

## Architecture & data flow

```
FCM data message ──▶ LemanMessagingService.onMessageReceived()
                        └▶ enqueue EXPEDITED SyncNotifyWorker
                              └▶ threadRepository.syncForNotifications()   (reuses SessionSyncer)
                                    └▶ returns List<SyncChange>
                              └▶ if app NOT foreground:
                                    MessageNotifier.post(changes)  ──▶ tap ▶ leman://thread/{id}

App start / onNewToken / toggle-on ──▶ DeviceRegistrar
                                         └▶ HermesClient.registerDevice(token, deviceId)
                                            POST {serverUrl}/api/devices  (resilient no-op)
```

Two independent flows — **inbound** (push → sync → notify) and **outbound** (token → register).
The existing 30s foreground `SyncScheduler` is untouched and posts **no** notifications:
notifications are strictly a background/push concern, so the user is never buzzed while actively
in the app.

## The notification signal

`SessionSyncer.syncOnce()` gains an optional `collect: ((SyncChange) -> Unit)? = null` parameter.
The foreground path passes `null`, so its behavior is unchanged. For each thread the syncer
**rebuilds** (i.e. passed the existing `serverLastActive` change-detection check), it reports a
change **when the newest `user`/`agent` turn is an `agent` turn**:

```kotlin
data class SyncChange(
    val threadId: String,
    val title: String,
    val preview: String,
    val isNewSession: Boolean,   // local == null at rebuild time
    val serverLastActive: Long,  // dedup key
)
```

`ThreadRepository` exposes `suspend fun syncForNotifications(): ApiResult<List<SyncChange>>`,
which runs `syncer.syncOnce(collect = …)` and returns the collected changes.

This rule yields exactly the chosen v1 scope:

| Situation | Newest turn | Result |
|---|---|---|
| New assistant reply from cron / another client | `agent` | **notify** |
| Reply to the user's own message that completed while backgrounded | `agent` | **notify** |
| User's own just-sent message | `user` | skip |
| Cron/system session with only a `[IMPORTANT:` preamble, no reply yet | system | skip |

Why not the existing `unread` flag: `SessionSyncer` deliberately marks brand-new rows
(`local == null`) **read** to suppress the first-sync flood, which would also swallow a genuinely
new `cron` session. The notification signal must therefore be derived independently of `unread`.

### Two guards on top of the raw signal

- **First-sync flood suppression** — a persisted `hasSeededSync` boolean (DataStore). Until the
  first successful full sync completes, `SyncNotifyWorker` posts nothing and just sets the flag.
  A fresh install would otherwise fire dozens of notifications.
- **Dedup** — a persisted `Map<threadId, lastNotifiedServerActive>` (DataStore). Notify only when
  a thread's `serverLastActive` exceeds the last value we notified for it, so repeated pushes and
  15-minute WorkManager retries for the same reply don't re-buzz. DataStore (not a new Room
  column) to avoid a schema migration for v1.

## Components

### New files (package `net.liquidx.leman.messaging`)

| File | Responsibility |
|---|---|
| `LemanMessagingService.kt` | `FirebaseMessagingService`. `onMessageReceived` → enqueue expedited `SyncNotifyWorker`. `onNewToken` → enqueue `DeviceRegistrationWorker`. Thin; all logic delegates to container objects reached via `(applicationContext as LemanApp).container`. |
| `SyncNotifyWorker.kt` | Expedited `CoroutineWorker`. Calls `syncForNotifications()`; if `ProcessLifecycleOwner` current state is below `STARTED`, calls `MessageNotifier.post(changes)`. `Result.retry()` (backoff) on network failure. |
| `MessageNotifier.kt` | Creates the `new_messages` channel (IMPORTANCE_HIGH). Applies the seeded + dedup guards. Builds one `NotificationCompat` per changed thread (notification id derived from `threadId`) under a group + summary. `PendingIntent` → `Intent(ACTION_VIEW, "leman://thread/{id}")`. No-ops when `POST_NOTIFICATIONS` is not granted. |
| `DeviceRegistrar.kt` | Fetches the FCM token, POSTs it via `HermesClient.registerDevice`; enqueues `DeviceRegistrationWorker` (network constraint + backoff) on failure. |
| `DeviceRegistrationWorker.kt` | Retryable token registration. |

### Changes to existing files

- `data/repo/SessionSyncer.kt` — add the optional `collect` callback; emit `SyncChange` on rebuild
  when newest user/agent turn is `agent`.
- `data/repo/ThreadRepository.kt` — add `syncForNotifications()`.
- `data/remote/HermesClient.kt` + `OkHttpHermesClient.kt` — add `suspend fun registerDevice(token, deviceId): ApiResult<Unit>`
  and `suspend fun unregisterDevice(deviceId): ApiResult<Unit>` (opt-out, `DELETE /api/devices/{id}`).
- `data/settings/` — add `notificationsEnabled: Boolean`, `deviceId: String` (stable UUID,
  generated once), `hasSeededSync: Boolean`, `notifiedActive: Map<String, Long>`.
- `di/AppContainer.kt` — add lazy singletons `messageNotifier`, `deviceRegistrar`, `deviceId`.
- `AndroidManifest.xml` — add `POST_NOTIFICATIONS` permission and the `<service>` for
  `LemanMessagingService` (the `leman://thread/{id}` deep link is already declared).
- `LemanApp.kt` — trigger `DeviceRegistrar` on start when notifications are enabled and a key is set.

## Token registration contract (client's assumption)

```
POST {serverUrl}/api/devices
Authorization: Bearer <HERMES_API_KEY>
Content-Type: application/json
{ "fcm_token": "<token>", "device_id": "<stable-uuid>", "platform": "android" }
```

- `device_id` — a stable UUID generated once and persisted in settings; lets the server replace a
  rotated token for the same device rather than accumulating duplicates.
- Absent endpoint today: `404`/unset URL → log + retry, no crash, no user-facing error.
- **Not yet server-verified** — to be recorded as an assumption in the gateway-contract memory and
  confirmed when the server project is built.

### De-registration (opt-out half)

Turning the Notifications toggle off does more than persist a flag: it also stops the server from
pushing and disables local FCM delivery, so `SyncNotifyWorker` isn't woken for a setting the user
has already switched off.

```
DELETE {serverUrl}/api/devices/{device_id}
Authorization: Bearer <HERMES_API_KEY>
```

- Same `device_id` as registration. `HermesClient.unregisterDevice(deviceId)` sends the request;
  `DeviceRegistrar.unregister()` treats both a `200` and a `404` (endpoint not built yet) as
  success-equivalent — a `404` here is expected, not an error, mirroring the registration contract's
  resilient-no-op stance.
- Order of operations matters: the server call is attempted **first**, then local delivery is
  disabled — so a failed de-registration attempt never loses the token before the server has been
  told about it.
- Opt-out also flips `FirebaseMessaging.isAutoInitEnabled` back to `false` and deletes the local FCM
  token (`FirebaseMessaging.deleteToken()`), regardless of whether the server call succeeded — this
  is what actually stops the device from receiving pushes, independent of server-side state.
- **Not yet server-verified** — same caveat as registration; the `DELETE /api/devices/{id}` route
  doesn't exist on the server yet.
- Wired from the UI toggle via `DeviceUnregistrationWorker` (mirrors `DeviceRegistrationWorker`),
  enqueued as unique work `"unregister-device"`. Since `"register-device"` and `"unregister-device"`
  are separate unique-work names, each enqueue cancels the other so a rapid toggle can't leave both
  queued in a contradictory order.

## Permissions & settings UI

`minSdk 34` → `POST_NOTIFICATIONS` is always a runtime prompt. A single global **Notifications**
toggle in the Config screen:

- Flipping it **on** requests the runtime permission (`rememberLauncherForActivityResult`) and
  kicks off device registration. Denied permission reflects the toggle back to off.
- On app start, if the toggle is on but the permission was revoked, reflect the true state.
- Per-thread "needs me / everything" mute is explicitly deferred (the settings screen for it is
  not yet designed — `docs/design/README.md`).

## Firebase & Gradle setup

- Version catalog + Gradle wiring for the `com.google.gms.google-services` plugin and
  `firebase-messaging` via the Firebase BoM.
- **Gotcha:** debug builds use applicationId `net.liquidx.leman.debug`. The Firebase project must
  register **both** `net.liquidx.leman` and `net.liquidx.leman.debug`, or the google-services
  plugin fails with "No matching client found for package name."
- User performs the console steps (create project, add both packages, download
  `google-services.json` into `app/`), guided click-by-click at implementation time. `google-services.json`
  is git-ignored / excluded from backup consistent with existing secret handling.

## Known constraints (documented, not blockers)

- **Data-only FCM messages require `priority: high`** to reliably wake the app in Doze, and are
  **not delivered while the app is force-stopped** by the user — inherent to FCM. The server sets
  high priority.
- **WorkManager expedited quota** can defer work under sustained Doze; acceptable here.
- Latency is near-real-time when a push arrives; there is no polling fallback in this client (that
  was Option B, explicitly not chosen).

## Testing

- `SessionSyncer` change-collection + seeded/dedup guards → Robolectric + in-memory Room (existing
  harness in `docs/spec/07-testing.md`).
- `DeviceRegistrar` / `registerDevice` → MockWebServer (existing dep), including the 404
  resilient-no-op path.
- Services and Workers kept thin so logic lives in unit-testable container objects; the Worker via
  the WorkManager test harness.
- Manual e2e: Firebase console "send test message" to the logged token, plus the existing
  `FakeHermesServer` for the sync side.

## Out of scope (this spec)

- The notification server (separate project + spec).
- Per-thread mute preferences.
- A background polling fallback for when push is undelivered (Option B in the earlier analysis).
