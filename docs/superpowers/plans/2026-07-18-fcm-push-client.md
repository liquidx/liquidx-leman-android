# FCM Push Notification Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notify the user of new agent messages while the app is backgrounded/closed, by having an FCM data push wake a background worker that reuses the existing sync and posts a local notification.

**Architecture:** Signal-to-sync. An FCM data message enqueues an expedited WorkManager job that (1) reconfigures the Hermes client transport from stored settings, (2) runs a change-collecting variant of the existing `SessionSyncer`, and (3) posts a local notification per newly-arrived agent turn. A separate outbound flow registers the device's FCM token with the (not-yet-built) server at `POST /api/devices`, tolerating a missing endpoint. The gateway stays the single source of truth; Room stays a cache.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, kotlinx.serialization, Room, DataStore, **new:** Firebase Cloud Messaging (`firebase-messaging` via Firebase BoM), WorkManager (`androidx.work`), manual DI (no Hilt).

## Global Constraints

- **minSdk = 34**, targetSdk = 36, compileSdk = 36.1. `POST_NOTIFICATIONS` is therefore always a runtime permission.
- **applicationId** = `net.liquidx.leman`; **debug builds append `.debug`** (`net.liquidx.leman.debug`). The Firebase project MUST register **both** package names or the google-services plugin fails.
- **Manual DI only** — no Hilt/Koin. New singletons go in `di/AppContainer.kt`; framework-instantiated classes (services, workers) reach the graph via `(applicationContext as LemanApp).container`.
- **Error taxonomy:** every network call returns `ApiResult<T>` (`Ok`/`Err(ApiError)`); `ApiError` variants are `Network`, `Timeout`, `Auth(code)`, `Server(code,msg)`, `Client(code,msg)`, `Protocol(detail)`, `NotConfigured`.
- **Notifications default OFF.** Nothing registers a token or posts a notification unless the user enables the global toggle.
- **JSON:** use the shared `HermesJson` instance (`data/remote/Dto.kt`) for (de)serialization.
- **Token registration contract (client's assumption, not yet server-verified):**
  `POST {serverUrl}/api/devices`, `Authorization: Bearer <key>`, body `{"fcm_token","device_id","platform":"android"}`.
- Any new `HermesClient` interface method must be implemented in **all four** implementors:
  `OkHttpHermesClient` (main), `FakeHermesClient` (test), `FakeHermesServer` (debug), and the switching wrapper in `debug/FakeHermesServer.kt` (~line 295).

**Task ordering rationale:** Tasks 1–6 are pure logic + DTOs and compile with **no** Firebase/WorkManager dependency. Task 7 adds those dependencies (and the user's Firebase console step). Task 8 wires the DI graph. The workers/service (Tasks 9–10) reference DI members, so they must come **after** Task 8. App-level wiring (Tasks 11–13) references the workers, so it comes last. Every task's verify step compiles cleanly in this order.

---

## File Structure

**New (main, package `net.liquidx.leman.messaging`):** `LemanMessagingService.kt`, `SyncNotifyWorker.kt`, `MessageNotifier.kt`, `DeviceRegistrar.kt`, `DeviceRegistrationWorker.kt`, `Fcm.kt`. **New (settings):** `data/settings/PushPrefsStore.kt`. **New (res):** `res/drawable/ic_stat_notify.xml`.

**Modified (main):** `data/repo/SessionSyncer.kt`, `data/repo/ThreadRepository.kt`, `data/remote/{HermesClient,OkHttpHermesClient,Dto}.kt`, `domain/model/Settings.kt`, `data/settings/SettingsStore.kt`, `di/AppContainer.kt`, `LemanApp.kt`, `ui/config/{ConfigViewModel,ConfigScreen}.kt`, `ui/nav/LemanNavHost.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`.

**Modified (test/debug):** `testutil/FakeHermesClient.kt`, `debug/FakeHermesServer.kt`.

---

## Task 1: PushPrefsStore (internal push state)

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/data/settings/PushPrefsStore.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/settings/PushPrefsStoreTest.kt`

**Interfaces:**
- Produces: `class PushPrefsStore(scope, produceFile)` with secondary `(context, scope)` ctor; `suspend fun deviceId(): String` (stable, generated once), `suspend fun hasSeeded(): Boolean`, `suspend fun markSeeded()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.liquidx.leman.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushPrefsStoreTest {

    private fun newStore(scope: kotlinx.coroutines.CoroutineScope): PushPrefsStore {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "push-test-${System.nanoTime()}",
        )
        return PushPrefsStore(scope) { File(dir, "push.preferences_pb") }
    }

    @Test
    fun deviceId_isStableAcrossReads() = runTest {
        val store = newStore(this)
        val first = store.deviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, store.deviceId())
    }

    @Test
    fun seeded_defaultsFalse_thenSticks() = runTest {
        val store = newStore(this)
        assertFalse(store.hasSeeded())
        store.markSeeded()
        assertTrue(store.hasSeeded())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.settings.PushPrefsStoreTest"`
Expected: FAIL — `PushPrefsStore` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.liquidx.leman.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Internal state for the push subsystem — NOT user-facing settings. Kept in its
 * own DataStore file so notification bookkeeping never mixes with [SettingsStore].
 */
class PushPrefsStore(
    scope: CoroutineScope,
    produceFile: () -> File,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope,
        { File(context.filesDir, "datastore/push.preferences_pb") },
    )

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val hasSeeded = booleanPreferencesKey("has_seeded_sync")
    }

    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)

    /** A stable per-install id, generated once so a rotated token replaces (not duplicates) this device. */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        var result = fresh
        store.edit { p -> result = p[Keys.deviceId] ?: fresh.also { p[Keys.deviceId] = it } }
        return result
    }

    suspend fun hasSeeded(): Boolean = store.data.first()[Keys.hasSeeded] ?: false

    suspend fun markSeeded() {
        store.edit { it[Keys.hasSeeded] = true }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.settings.PushPrefsStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/data/settings/PushPrefsStore.kt \
        app/src/test/java/net/liquidx/leman/data/settings/PushPrefsStoreTest.kt
git commit -m "feat: PushPrefsStore for deviceId + seeded flag"
```

---

## Task 2: notificationsEnabled setting

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/domain/model/Settings.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/settings/SettingsStore.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/settings/SettingsStoreTest.kt` (extend existing)

**Interfaces:**
- Produces: `Settings.notificationsEnabled: Boolean = false`; persisted under DataStore key `notifications_enabled`.

- [ ] **Step 1: Write the failing test** — add to `SettingsStoreTest`:

```kotlin
    @Test
    fun notificationsEnabled_defaultsFalse_persists() = runTest {
        val store = newStore(this)
        assertEquals(false, store.settings.first().notificationsEnabled)
        store.update { it.copy(notificationsEnabled = true) }
        assertEquals(true, store.settings.first().notificationsEnabled)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.settings.SettingsStoreTest"`
Expected: FAIL — `notificationsEnabled` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `Settings.kt`, add the field (after `showToolArgs`):

```kotlin
    val showToolArgs: Boolean = true,
    val notificationsEnabled: Boolean = false,
```

In `SettingsStore.kt`, add the key:

```kotlin
        val showToolArgs = booleanPreferencesKey("show_tool_args")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
```

In **both** the `settings` flow map and `update`'s `current` builder, add:

```kotlin
            notificationsEnabled = p[Keys.notificationsEnabled] ?: false,
```

In `update`'s write block, add:

```kotlin
            p[Keys.notificationsEnabled] = next.notificationsEnabled
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.settings.SettingsStoreTest"`
Expected: PASS (existing + new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/domain/model/Settings.kt \
        app/src/main/java/net/liquidx/leman/data/settings/SettingsStore.kt \
        app/src/test/java/net/liquidx/leman/data/settings/SettingsStoreTest.kt
git commit -m "feat: add notificationsEnabled setting"
```

---

## Task 3: SyncChange collection in SessionSyncer

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/SessionSyncer.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/ThreadRepository.kt:84`
- Test: `app/src/test/java/net/liquidx/leman/data/repo/SessionSyncerTest.kt` (extend)

**Interfaces:**
- Produces: `data class SyncChange(threadId, title, preview, isNewSession, serverLastActive)`;
  `suspend fun SessionSyncer.syncOnce(collect: ((SyncChange) -> Unit)? = null): ApiResult<Unit>`;
  `suspend fun ThreadRepository.syncForNotifications(): ApiResult<List<SyncChange>>`.
- Rule: collect for a rebuilt thread **iff its newest `user`/`agent` turn is an `agent` turn.**

- [ ] **Step 1: Write the failing test** — add to `SessionSyncerTest`:

```kotlin
    private fun TestScope.collect(
        active: Set<String> = emptySet(),
        visible: String? = null,
    ): Pair<ApiResult<Unit>, List<SyncChange>> {
        val changes = mutableListOf<SyncChange>()
        val result = kotlinx.coroutines.runBlocking { syncer(active, visible).syncOnce { changes += it } }
        return result to changes
    }

    @Test
    fun collect_reportsNewAgentReply() = runTest {
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        client.messagesBySession["run_x"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "q", timestamp = 190.0),
                SessionMessageDto(2, "assistant", "the answer", timestamp = 195.0),
            ),
        )
        val (result, changes) = collect()
        assertTrue(result is ApiResult.Ok)
        assertEquals(1, changes.size)
        assertEquals("run_x", changes.single().threadId)
        assertTrue(changes.single().isNewSession)
        assertEquals(200_000L, changes.single().serverLastActive)
    }

    @Test
    fun collect_skipsWhenNewestTurnIsUserOrSystem() = runTest {
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_cron", 200.0, source = "cron")), hasMore = false)),
        )
        client.messagesBySession["run_cron"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "digest", timestamp = 100.0),
                SessionMessageDto(2, "assistant", "sent", timestamp = 110.0, finishReason = "stop"),
                SessionMessageDto(3, "user", "[IMPORTANT: cron preamble]", timestamp = 190.0),
            ),
        )
        val (_, changes) = collect()
        assertTrue(changes.isEmpty())
    }

    @Test
    fun collect_skipsUnchangedThread() = runTest {
        seedThread("run_x", lastActiveAt = 200_000, serverLastActive = 200_000)
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        val (_, changes) = collect()
        assertTrue(changes.isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.repo.SessionSyncerTest"`
Expected: FAIL — `syncOnce` takes no args / `SyncChange` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `SessionSyncer.kt`, add the data class below the imports:

```kotlin
/** A notification-worthy advance surfaced during a sync (a rebuilt thread whose newest turn is an agent reply). */
data class SyncChange(
    val threadId: String,
    val title: String,
    val preview: String,
    val isNewSession: Boolean,
    val serverLastActive: Long,
)
```

Change the signature:

```kotlin
    suspend fun syncOnce(collect: ((SyncChange) -> Unit)? = null): ApiResult<Unit> {
```

Immediately **after** `val rebuilt = ThreadEntity(...)` and **before** the `db.withTransaction { … }` block, insert:

```kotlin
            if (collect != null) {
                val newestVisible = turns.lastOrNull { it.kind == "user" || it.kind == "agent" }
                if (newestVisible?.kind == "agent") {
                    collect(
                        SyncChange(
                            threadId = session.id,
                            title = rebuilt.title,
                            preview = rebuilt.preview,
                            isNewSession = local == null,
                            serverLastActive = lastActiveMs,
                        ),
                    )
                }
            }
```

In `ThreadRepository.kt`, after `syncNow()` (line 84), add:

```kotlin
    /**
     * Background/push sync that reports notification-worthy advances (FCM push client).
     * Foreground sync uses [syncNow] with no collector, so its behavior is unchanged.
     */
    suspend fun syncForNotifications(): ApiResult<List<SyncChange>> {
        val changes = mutableListOf<SyncChange>()
        return syncer.syncOnce { changes += it }.map { changes }
    }
```

Add `import net.liquidx.leman.domain.model.map` if unresolved.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.repo.SessionSyncerTest" --tests "net.liquidx.leman.data.repo.ThreadRepositoryTest"`
Expected: PASS. Pre-existing `SessionSyncerTest` cases still pass (they call `syncOnce()` with no arg).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/data/repo/SessionSyncer.kt \
        app/src/main/java/net/liquidx/leman/data/repo/ThreadRepository.kt \
        app/src/test/java/net/liquidx/leman/data/repo/SessionSyncerTest.kt
git commit -m "feat: collect notification-worthy changes during sync"
```

---

## Task 4: HermesClient.registerDevice

**Files:**
- Modify: `data/remote/HermesClient.kt`, `OkHttpHermesClient.kt`, `Dto.kt`
- Modify: `app/src/test/java/net/liquidx/leman/testutil/FakeHermesClient.kt`
- Modify: `app/src/debug/java/net/liquidx/leman/debug/FakeHermesServer.kt` (both implementors: `FakeHermesServer` ~L32 and the switching wrapper ~L295)
- Test: `app/src/test/java/net/liquidx/leman/data/remote/OkHttpHermesClientTest.kt` (extend)

**Interfaces:**
- Produces: `suspend fun HermesClient.registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit>` → `POST api/devices`.

- [ ] **Step 1: Write the failing test** — add to `OkHttpHermesClientTest`:

```kotlin
    @Test
    fun registerDevice_postsContract() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client.registerDevice("tok123", "dev-uuid") is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/devices", req.path)
        assertEquals("Bearer testkey", req.getHeader("Authorization"))
        assertEquals(
            """{"fcm_token":"tok123","device_id":"dev-uuid","platform":"android"}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun registerDevice_404_isClient() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        assertTrue(client.registerDevice("t", "d").errorOrNull() is ApiError.Client)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.remote.OkHttpHermesClientTest"`
Expected: FAIL — `registerDevice` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `Dto.kt`, add near the other request DTOs (field order fixed to match the asserted JSON):

```kotlin
@kotlinx.serialization.Serializable
data class DeviceRegistrationDto(
    @kotlinx.serialization.SerialName("fcm_token") val fcmToken: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    val platform: String = "android",
)
```

In `HermesClient.kt` interface (after `deleteSession`):

```kotlin
    /** Registers this device's FCM token so the server can push (FCM push client). */
    suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit>
```

In `OkHttpHermesClient.kt` (after `deleteSession`):

```kotlin
    override suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(
            DeviceRegistrationDto.serializer(),
            DeviceRegistrationDto(fcmToken, deviceId),
        )
        val request = t.request("api/devices").post(body.toRequestBody(jsonMediaType)).build()
        return execute(t.rest, request) { }
    }
```

In `testutil/FakeHermesClient.kt`:

```kotlin
    val registerDeviceCalls = mutableListOf<Pair<String, String>>()
    var registerDeviceResult: ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit> {
        registerDeviceCalls += fcmToken to deviceId
        return registerDeviceResult
    }
```

In `debug/FakeHermesServer.kt`, add to **both**:

```kotlin
    // FakeHermesServer (~L32):
    override suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    // switching wrapper (~L295):
    override suspend fun registerDevice(fcmToken: String, deviceId: String) = real.registerDevice(fcmToken, deviceId)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.remote.OkHttpHermesClientTest" && ./gradlew compileDebugKotlin`
Expected: PASS + debug compiles (all four implementors).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/data/remote/ \
        app/src/test/java/net/liquidx/leman/testutil/FakeHermesClient.kt \
        app/src/debug/java/net/liquidx/leman/debug/FakeHermesServer.kt \
        app/src/test/java/net/liquidx/leman/data/remote/OkHttpHermesClientTest.kt
git commit -m "feat: HermesClient.registerDevice (POST /api/devices)"
```

---

## Task 5: DeviceRegistrar

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/messaging/DeviceRegistrar.kt`
- Test: `app/src/test/java/net/liquidx/leman/messaging/DeviceRegistrarTest.kt`

**Interfaces:**
- Produces: `class DeviceRegistrar(client, settingsStore, apiKeyStore, pushPrefs, tokenProvider: suspend () -> String?)`;
  `enum class Outcome { DONE, RETRY_LATER, GAVE_UP }`; `suspend fun register(): Outcome`.
- Policy: disabled → DONE. No key → GAVE_UP. No token → RETRY_LATER. Network/Timeout/Server → RETRY_LATER. Client(404)/Auth/Protocol/NotConfigured → GAVE_UP.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.liquidx.leman.messaging

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.settings.PushPrefsStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.testutil.FakeApiKeyStore
import net.liquidx.leman.testutil.FakeHermesClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRegistrarTest {

    private fun registrar(
        scope: kotlinx.coroutines.CoroutineScope,
        client: FakeHermesClient,
        key: String? = "k",
        token: String? = "tok",
    ): Pair<DeviceRegistrar, SettingsStore> {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "reg-${System.nanoTime()}",
        )
        val settings = SettingsStore(scope) { File(dir, "settings.pb") }
        val push = PushPrefsStore(scope) { File(dir, "push.pb") }
        return DeviceRegistrar(client, settings, FakeApiKeyStore(key), push) { token } to settings
    }

    @Test
    fun disabled_isDoneWithoutCalling() = runTest {
        val client = FakeHermesClient()
        val (reg, _) = registrar(this, client) // notificationsEnabled defaults false
        assertEquals(DeviceRegistrar.Outcome.DONE, reg.register())
        assertTrue(client.registerDeviceCalls.isEmpty())
    }

    @Test
    fun enabled_registersToken() = runTest {
        val client = FakeHermesClient()
        val (reg, settings) = registrar(this, client)
        settings.update { it.copy(notificationsEnabled = true) }
        assertEquals(DeviceRegistrar.Outcome.DONE, reg.register())
        assertEquals("tok", client.registerDeviceCalls.single().first)
    }

    @Test
    fun networkError_retriesLater() = runTest {
        val client = FakeHermesClient().apply {
            registerDeviceResult = ApiResult.Err(ApiError.Network(IOException("down")))
        }
        val (reg, settings) = registrar(this, client)
        settings.update { it.copy(notificationsEnabled = true) }
        assertEquals(DeviceRegistrar.Outcome.RETRY_LATER, reg.register())
    }

    @Test
    fun missingEndpoint404_givesUp() = runTest {
        val client = FakeHermesClient().apply {
            registerDeviceResult = ApiResult.Err(ApiError.Client(404, "no route"))
        }
        val (reg, settings) = registrar(this, client)
        settings.update { it.copy(notificationsEnabled = true) }
        assertEquals(DeviceRegistrar.Outcome.GAVE_UP, reg.register())
    }

    @Test
    fun noKey_givesUp() = runTest {
        val client = FakeHermesClient()
        val (reg, settings) = registrar(this, client, key = null)
        settings.update { it.copy(notificationsEnabled = true) }
        assertEquals(DeviceRegistrar.Outcome.GAVE_UP, reg.register())
        assertTrue(client.registerDeviceCalls.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.DeviceRegistrarTest"`
Expected: FAIL — `DeviceRegistrar` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.liquidx.leman.messaging

import kotlinx.coroutines.flow.first
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.PushPrefsStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult

/**
 * Outbound half of the push client: registers this device's FCM token with the
 * gateway. Resilient — a missing endpoint or absent key never crashes; it just
 * stops and is re-triggered on the next app start or token rotation. The
 * [HermesClient] transport must already be configured by the caller.
 */
class DeviceRegistrar(
    private val client: HermesClient,
    private val settingsStore: SettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val pushPrefs: PushPrefsStore,
    private val tokenProvider: suspend () -> String?,
) {
    enum class Outcome { DONE, RETRY_LATER, GAVE_UP }

    suspend fun register(): Outcome {
        if (!settingsStore.settings.first().notificationsEnabled) return Outcome.DONE
        if (apiKeyStore.get().isNullOrBlank()) return Outcome.GAVE_UP
        val token = tokenProvider() ?: return Outcome.RETRY_LATER
        return when (val r = client.registerDevice(token, pushPrefs.deviceId())) {
            is ApiResult.Ok -> Outcome.DONE
            is ApiResult.Err -> when (r.error) {
                is ApiError.Network, ApiError.Timeout, is ApiError.Server -> Outcome.RETRY_LATER
                else -> Outcome.GAVE_UP
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.DeviceRegistrarTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/messaging/DeviceRegistrar.kt \
        app/src/test/java/net/liquidx/leman/messaging/DeviceRegistrarTest.kt
git commit -m "feat: DeviceRegistrar (resilient token registration)"
```

---

## Task 6: MessageNotifier + status icon

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/messaging/MessageNotifier.kt`
- Create: `app/src/main/res/drawable/ic_stat_notify.xml`
- Test: `app/src/test/java/net/liquidx/leman/messaging/MessageNotifierTest.kt`

**Interfaces:**
- Produces: `class MessageNotifier(context, permissionGranted: () -> Boolean = …)`; `fun post(changes: List<SyncChange>)`; `const val CHANNEL_ID = "new_messages"`.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.liquidx.leman.messaging

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.liquidx.leman.data.repo.SyncChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MessageNotifierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun change(id: String) =
        SyncChange(id, "Title $id", "preview $id", isNewSession = false, serverLastActive = 1L)

    @Test
    fun post_granted_showsPerChange_andCreatesChannel() {
        val notifier = MessageNotifier(context, permissionGranted = { true })
        notifier.post(listOf(change("a"), change("b")))
        assertTrue(
            nm.notificationChannels.any {
                (it as android.app.NotificationChannel).id == MessageNotifier.CHANNEL_ID
            },
        )
        val titles = nm.activeNotifications.map { it.notification.extras.getString("android.title") }
        assertTrue(titles.contains("Title a"))
        assertTrue(titles.contains("Title b"))
    }

    @Test
    fun post_denied_showsNothing() {
        MessageNotifier(context, permissionGranted = { false }).post(listOf(change("a")))
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun post_empty_noop() {
        MessageNotifier(context, permissionGranted = { true }).post(emptyList())
        assertEquals(0, nm.activeNotifications.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.MessageNotifierTest"`
Expected: FAIL — `MessageNotifier` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `res/drawable/ic_stat_notify.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,2C6.48,2 2,6.03 2,11c0,2.7 1.33,5.12 3.44,6.78L4,22l5.1,-2.04C10.03,20.31 11,20.4 12,20.4c5.52,0 10,-4.03 10,-9.4S17.52,2 12,2z"/>
</vector>
```

Create `MessageNotifier.kt`:

```kotlin
package net.liquidx.leman.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.liquidx.leman.MainActivity
import net.liquidx.leman.R
import net.liquidx.leman.data.repo.SyncChange

/**
 * Posts one local notification per new agent reply. Tapping deep-links to the
 * thread via `leman://thread/{id}` (declared on the THREAD destination). No-ops
 * without POST_NOTIFICATIONS. Channel is created lazily and idempotently.
 */
class MessageNotifier(
    private val context: Context,
    private val permissionGranted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    fun post(changes: List<SyncChange>) {
        if (changes.isEmpty() || !permissionGranted()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(context)
        for (c in changes) nm.notify(c.threadId.hashCode(), build(c))
        if (changes.size > 1) nm.notify(SUMMARY_ID, buildSummary(changes.size))
    }

    private fun build(c: SyncChange): android.app.Notification {
        val intent = Intent(Intent.ACTION_VIEW, "leman://thread/${c.threadId}".toUri())
            .setClass(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            c.threadId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(c.title)
            .setContentText(c.preview)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentIntent(pi)
            .build()
    }

    private fun buildSummary(count: Int): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("$count new messages")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_ID = "new_messages"
        private const val GROUP = "net.liquidx.leman.NEW_MESSAGES"
        private const val SUMMARY_ID = -1
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.MessageNotifierTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/messaging/MessageNotifier.kt \
        app/src/main/res/drawable/ic_stat_notify.xml \
        app/src/test/java/net/liquidx/leman/messaging/MessageNotifierTest.kt
git commit -m "feat: MessageNotifier posts per-thread notifications"
```

---

## Task 7: Firebase + WorkManager build wiring (USER CONSOLE STEP)

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, `.gitignore`
- Add: `app/google-services.json` (downloaded by the user)
- Create: `app/src/main/java/net/liquidx/leman/messaging/Fcm.kt`

**Manual prerequisite. Present these to the user; wait for `google-services.json` before building.**

> **Firebase console steps (user):**
> 1. <https://console.firebase.google.com> → **Add project** (or pick existing). Analytics optional.
> 2. Click the **Android** icon ("Add app"). **Package name:** `net.liquidx.leman`, nickname "LeMan (release)". Register → **Skip** the SDK steps (Next → Next → Continue to console).
> 3. **Add app → Android** again. **Package name:** `net.liquidx.leman.debug`, nickname "LeMan (debug)". Register.
> 4. Project settings (gear) → **Your apps** → **Download google-services.json**. It contains *both* clients (shared project). Place it at `app/google-services.json`.
> 5. FCM (Cloud Messaging API v1) is on by default — no server key needed for the client.

- [ ] **Step 1: Version catalog** — in `gradle/libs.versions.toml`:

```toml
firebaseBom = "34.0.0"
googleServices = "4.4.2"
work = "2.10.1"
```

```toml
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
```

```toml
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

- [ ] **Step 2: Root build** — `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}
```

- [ ] **Step 3: App build** — `app/build.gradle.kts`: add `alias(libs.plugins.google.services)` to `plugins { }`; add to `dependencies { }`:

```kotlin
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.androidx.work.testing)
```

- [ ] **Step 4: gitignore** — append to `.gitignore`:

```
# Firebase config (contains project identifiers; regenerate per environment)
app/google-services.json
```

- [ ] **Step 5: Manifest permission** — add after the existing `<uses-permission>` lines:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

(The `<service>` for `LemanMessagingService` is added in Task 10, alongside the class itself, so the manifest never references a missing class.)

- [ ] **Step 6: FCM token helper** — create `messaging/Fcm.kt`:

```kotlin
package net.liquidx.leman.messaging

import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Current FCM registration token, or null if it can't be fetched. No coroutines-play-services dep. */
suspend fun fetchFcmToken(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        cont.resume(if (task.isSuccessful) task.result else null)
    }
}
```

- [ ] **Step 7: Verify** (after `google-services.json` is present):

Run: `./gradlew :app:processDebugGoogleServices && ./gradlew compileDebugKotlin`
Expected: SUCCESS. If it fails with "No matching client found for package name 'net.liquidx.leman.debug'", the user missed console step 3.

- [ ] **Step 8: Commit** (NOT `google-services.json`):

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts \
        app/src/main/AndroidManifest.xml .gitignore \
        app/src/main/java/net/liquidx/leman/messaging/Fcm.kt
git commit -m "build: wire Firebase Messaging, WorkManager, google-services"
```

---

## Task 8: DI wiring + foreground seeding

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/di/AppContainer.kt`

**Interfaces:**
- Produces on `AppContainer`: `val pushPrefs: PushPrefsStore`, `val messageNotifier: MessageNotifier`, `val deviceRegistrar: DeviceRegistrar`, `suspend fun configurePushClient()`. Foreground sync now marks seeded once.
- No worker references here (workers come later), so this compiles on top of Tasks 1–7.

- [ ] **Step 1: Add imports**

```kotlin
import kotlinx.coroutines.flow.first
import net.liquidx.leman.data.settings.PushPrefsStore
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.messaging.DeviceRegistrar
import net.liquidx.leman.messaging.MessageNotifier
import net.liquidx.leman.messaging.fetchFcmToken
```

- [ ] **Step 2: Extend `Overrides`** (lets tests inject a fake):

```kotlin
        val pushPrefs: PushPrefsStore? = null,
```

- [ ] **Step 3: Add singletons** (after the `settings` singleton):

```kotlin
    val pushPrefs: PushPrefsStore by lazy { overrides.pushPrefs ?: PushPrefsStore(appContext, appScope) }

    val messageNotifier: MessageNotifier by lazy { MessageNotifier(appContext) }

    val deviceRegistrar: DeviceRegistrar by lazy {
        DeviceRegistrar(
            client = hermesClient,
            settingsStore = settings,
            apiKeyStore = apiKeyStore,
            pushPrefs = pushPrefs,
            tokenProvider = { fetchFcmToken() },
        )
    }

    /**
     * An FCM-launched process never runs ConnectionManager.reconfigure(), so a
     * background worker must configure the client transport itself before any call.
     */
    suspend fun configurePushClient() {
        hermesClient.reconfigure(settings.settings.first().serverUrl, apiKeyStore.get())
    }
```

- [ ] **Step 4: Seed on first foreground sync** — replace the `syncScheduler` singleton:

```kotlin
    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(
            syncNow = {
                val result = threadRepository.syncNow()
                if (result is ApiResult.Ok && !pushPrefs.hasSeeded()) pushPrefs.markSeeded()
            },
            connState = connectionManager.state,
            scope = appScope,
        )
    }
```

- [ ] **Step 5: Verify build + existing tests**

Run: `./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest --tests "net.liquidx.leman.data.repo.SyncSchedulerTest"`
Expected: SUCCESS + PASS (the scheduler's `suspend` lambda still satisfies `suspend () -> Unit`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/di/AppContainer.kt
git commit -m "feat: wire push subsystem into DI + seed on foreground sync"
```

---

## Task 9: DeviceRegistrationWorker

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/messaging/DeviceRegistrationWorker.kt`
- Test: `app/src/test/java/net/liquidx/leman/messaging/DeviceRegistrationWorkerTest.kt`

**Interfaces:**
- Consumes: `LemanApp.container.deviceRegistrar`, `container.configurePushClient()` (Task 8).
- Produces: `class DeviceRegistrationWorker(ctx, params) : CoroutineWorker`; `object RegistrationResult { fun of(Outcome): ListenableWorker.Result }`.

- [ ] **Step 1: Write the failing test** (pure mapping):

```kotlin
package net.liquidx.leman.messaging

import androidx.work.ListenableWorker
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistrationWorkerTest {
    @Test fun done_isSuccess() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.DONE) is ListenableWorker.Result.Success)
    @Test fun gaveUp_isSuccess() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.GAVE_UP) is ListenableWorker.Result.Success)
    @Test fun retryLater_isRetry() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.RETRY_LATER) is ListenableWorker.Result.Retry)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.DeviceRegistrationWorkerTest"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.liquidx.leman.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import net.liquidx.leman.LemanApp

/** Retryable FCM-token registration. Delegates to [DeviceRegistrar]. */
class DeviceRegistrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LemanApp).container
        container.configurePushClient()
        return RegistrationResult.of(container.deviceRegistrar.register())
    }
}

/** Pure Outcome → WorkManager Result mapping (unit-tested without a WorkManager runtime). */
object RegistrationResult {
    fun of(outcome: DeviceRegistrar.Outcome): ListenableWorker.Result = when (outcome) {
        DeviceRegistrar.Outcome.DONE, DeviceRegistrar.Outcome.GAVE_UP -> ListenableWorker.Result.success()
        DeviceRegistrar.Outcome.RETRY_LATER -> ListenableWorker.Result.retry()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.DeviceRegistrationWorkerTest" && ./gradlew compileDebugKotlin`
Expected: PASS + SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/messaging/DeviceRegistrationWorker.kt \
        app/src/test/java/net/liquidx/leman/messaging/DeviceRegistrationWorkerTest.kt
git commit -m "feat: DeviceRegistrationWorker + Outcome→Result mapping"
```

---

## Task 10: SyncNotifyWorker + LemanMessagingService + manifest service

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/messaging/SyncNotifyWorker.kt`
- Create: `app/src/main/java/net/liquidx/leman/messaging/LemanMessagingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/net/liquidx/leman/messaging/PushDecisionTest.kt`

**Interfaces:**
- Consumes: `container.{settings,pushPrefs,threadRepository,messageNotifier,configurePushClient}`, `DeviceRegistrationWorker` (Task 9).
- Produces: `object PushDecision { fun toPost(wasSeeded, changes): List<SyncChange> }`; `SyncNotifyWorker`; `LemanMessagingService`.
- Gate: post only when `wasSeeded == true` AND app not foreground.

- [ ] **Step 1: Write the failing test** (pure decision):

```kotlin
package net.liquidx.leman.messaging

import net.liquidx.leman.data.repo.SyncChange
import org.junit.Assert.assertEquals
import org.junit.Test

class PushDecisionTest {
    private val changes = listOf(
        SyncChange("a", "A", "p", isNewSession = true, serverLastActive = 1L),
    )
    @Test fun notSeeded_suppresses() = assertEquals(emptyList<SyncChange>(), PushDecision.toPost(false, changes))
    @Test fun seeded_passesThrough() = assertEquals(changes, PushDecision.toPost(true, changes))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.PushDecisionTest"`
Expected: FAIL — `PushDecision` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `SyncNotifyWorker.kt`:

```kotlin
package net.liquidx.leman.messaging

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.liquidx.leman.LemanApp
import net.liquidx.leman.data.repo.SyncChange
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult

/**
 * Wakes on an FCM push, reconciles Room from the gateway (reusing SessionSyncer),
 * and posts a notification per new agent reply. Posts only when already seeded (so
 * the first-ever populate can't flood) and the app isn't foreground.
 */
class SyncNotifyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LemanApp).container
        if (!container.settings.settings.first().notificationsEnabled) return Result.success()

        container.configurePushClient()
        val wasSeeded = container.pushPrefs.hasSeeded()

        return when (val result = container.threadRepository.syncForNotifications()) {
            is ApiResult.Ok -> {
                container.pushPrefs.markSeeded()
                if (!isForeground()) {
                    container.messageNotifier.post(PushDecision.toPost(wasSeeded, result.value))
                }
                Result.success()
            }
            is ApiResult.Err -> when (result.error) {
                is ApiError.Network, ApiError.Timeout, is ApiError.Server -> Result.retry()
                else -> Result.success()
            }
        }
    }

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

/** Pure seed gate (unit-tested). Duplicate/retry pushes are deduped by the syncer's persisted serverLastActive. */
object PushDecision {
    fun toPost(wasSeeded: Boolean, changes: List<SyncChange>): List<SyncChange> =
        if (wasSeeded) changes else emptyList()
}
```

Create `LemanMessagingService.kt`:

```kotlin
package net.liquidx.leman.messaging

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/**
 * FCM entry point. A message is a lightweight "something changed" signal — its
 * payload is not trusted; the worker pulls fresh state from the gateway.
 */
class LemanMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val work = OneTimeWorkRequestBuilder<SyncNotifyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("sync-notify", ExistingWorkPolicy.KEEP, work)
    }

    override fun onNewToken(token: String) {
        val work = OneTimeWorkRequestBuilder<DeviceRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("register-device", ExistingWorkPolicy.REPLACE, work)
    }
}
```

Add the `<service>` inside `<application>` in `AndroidManifest.xml`:

```xml
        <service
            android:name=".messaging.LemanMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
```

- [ ] **Step 4: Run test + build**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.messaging.PushDecisionTest" && ./gradlew compileDebugKotlin`
Expected: PASS + SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/messaging/SyncNotifyWorker.kt \
        app/src/main/java/net/liquidx/leman/messaging/LemanMessagingService.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/java/net/liquidx/leman/messaging/PushDecisionTest.kt
git commit -m "feat: SyncNotifyWorker + LemanMessagingService"
```

---

## Task 11: App-start registration trigger

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/LemanApp.kt`

**Interfaces:**
- Consumes: `DeviceRegistrationWorker` (Task 9), `container.settings`.
- Produces: on app start, if notifications are enabled, enqueue a device registration.

- [ ] **Step 1: Implement** — in `LemanApp.onCreate`, after the lifecycle observer block, add:

```kotlin
        container.appScope.launch {
            if (container.settings.settings.first().notificationsEnabled) {
                androidx.work.WorkManager.getInstance(this@LemanApp).enqueueUniqueWork(
                    "register-device",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<net.liquidx.leman.messaging.DeviceRegistrationWorker>()
                        .setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build(),
                        )
                        .build(),
                )
            }
        }
```

Add imports: `import kotlinx.coroutines.flow.first`, `import kotlinx.coroutines.launch`.

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/LemanApp.kt
git commit -m "feat: enqueue device registration on app start when enabled"
```

---

## Task 12: MainActivity warm-start deep-link forwarding

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/MainActivity.kt`

Rationale: Compose Navigation auto-handles the launch intent on cold start, but a `singleTask` activity receives later notification taps via `onNewIntent`, which must be forwarded to the `NavController`.

- [ ] **Step 1: Implement** — replace the `setContent { … }` block so the NavController is hoisted and new intents are forwarded:

```kotlin
        setContent {
            LemanTheme {
                val navController = androidx.navigation.compose.rememberNavController()
                androidx.compose.runtime.DisposableEffect(navController) {
                    val listener = androidx.core.util.Consumer<Intent> { intent ->
                        navController.handleDeepLink(intent)
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }
                LemanNavHost(
                    container = container,
                    navController = navController,
                    onRevealKey = ::authenticateThen,
                    onShareExport = ::shareExport,
                )
            }
        }
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/MainActivity.kt
git commit -m "feat: forward warm-start deep-link intents to NavController"
```

---

## Task 13: Settings toggle + POST_NOTIFICATIONS permission

**Files:**
- Modify: `ui/config/ConfigViewModel.kt`, `ui/config/ConfigScreen.kt`, `ui/nav/LemanNavHost.kt`
- Test: `app/src/test/java/net/liquidx/leman/ui/config/ConfigViewModelTest.kt` (extend)

**Interfaces:**
- Produces: `ConfigEvent.SetNotificationsEnabled(enabled)`; a "notify on new messages" toggle; runtime permission requested on enable; registration enqueued on grant.

- [ ] **Step 1: Write the failing test** — add to `ConfigViewModelTest` (uses its existing `VmHarness`/`vm(h)` helpers and `awaitUntil`):

```kotlin
    @Test
    fun setNotificationsEnabled_persists() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.SetNotificationsEnabled(true))
            assertTrue(awaitUntil { it.settings.notificationsEnabled }.settings.notificationsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.ui.config.ConfigViewModelTest"`
Expected: FAIL — `SetNotificationsEnabled` unresolved.

- [ ] **Step 3: Implement**

In `ConfigViewModel.kt`, add the event (to the `ConfigEvent` sealed interface):

```kotlin
    data class SetNotificationsEnabled(val enabled: Boolean) : ConfigEvent
```

Add the handler branch in `onEvent`:

```kotlin
            is ConfigEvent.SetNotificationsEnabled -> update { it.copy(notificationsEnabled = event.enabled) }
```

In `ConfigScreen.kt`, add a parameter (defaulted so screenshot tests keep working without an ActivityResult host):

```kotlin
    onToggleNotifications: (Boolean) -> Unit = { onEvent(ConfigEvent.SetNotificationsEnabled(it)) },
```

Add a NOTIFICATIONS section just before `// ---- DATA ----`:

```kotlin
            // ---- NOTIFICATIONS --------------------------------------------
            SectionHeader("notifications")
            ToggleRow("notify on new messages", state.settings.notificationsEnabled, onToggleNotifications)
            Caption("pushes wake the app to check for new agent replies")
            SectionGap()
```

In `LemanNavHost.kt`, inside `composable(Routes.CONFIG)` (where `vm` and `context` exist), add a permission launcher and pass `onToggleNotifications` to `ConfigScreen`:

```kotlin
            val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted ->
                vm.onEvent(net.liquidx.leman.ui.config.ConfigEvent.SetNotificationsEnabled(granted))
                if (granted) enqueueRegister(context)
            }
```

Add these to the `ConfigScreen(...)` call:

```kotlin
                onToggleNotifications = { enabled ->
                    if (!enabled) {
                        vm.onEvent(net.liquidx.leman.ui.config.ConfigEvent.SetNotificationsEnabled(false))
                    } else if (
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.onEvent(net.liquidx.leman.ui.config.ConfigEvent.SetNotificationsEnabled(true))
                        enqueueRegister(context)
                    } else {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
```

Add a private helper at the bottom of `LemanNavHost.kt` (file scope):

```kotlin
private fun enqueueRegister(context: android.content.Context) {
    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
        "register-device",
        androidx.work.ExistingWorkPolicy.REPLACE,
        androidx.work.OneTimeWorkRequestBuilder<net.liquidx.leman.messaging.DeviceRegistrationWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build(),
            ).build(),
    )
}
```

- [ ] **Step 4: Run tests + build + screenshots**

Run: `./gradlew testDebugUnitTest --tests "net.liquidx.leman.ui.config.ConfigViewModelTest" && ./gradlew compileDebugKotlin`
Then update screenshot baselines for the new Config section:
`./gradlew testDebugUnitTest --tests "*ScreenshotTests*" -Proborazzi.test.record=true`
Expected: PASS + SUCCESS; new baseline recorded.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/liquidx/leman/ui/config/ConfigViewModel.kt \
        app/src/main/java/net/liquidx/leman/ui/config/ConfigScreen.kt \
        app/src/main/java/net/liquidx/leman/ui/nav/LemanNavHost.kt \
        app/src/test/java/net/liquidx/leman/ui/config/ConfigViewModelTest.kt \
        app/src/test/**/screenshot/
git commit -m "feat: notifications toggle + POST_NOTIFICATIONS permission flow"
```

---

## Task 14: Full verification + manual e2e

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite** — Run: `./gradlew testDebugUnitTest` → all pass.
- [ ] **Step 2: Assemble** — Run: `./gradlew assembleDebug` → SUCCESS (confirms `google-services.json` resolves both packages and the manifest `<service>` resolves the class).
- [ ] **Step 3: Manual e2e** (device/emulator with Google Play services):
  1. Install debug; Config → set server URL + API key; toggle **notify on new messages** ON; grant the permission prompt.
  2. Logcat: confirm a token is fetched and `POST /api/devices` is attempted (a 404 is expected until the server ships — it must not crash).
  3. Background the app. Firebase console → Cloud Messaging → **Send test message** → paste the device token → send a **data** message with `priority: high`.
  4. First cause a real new agent reply in a session (dashboard/cron) so the sync has something to surface, then send the push. Expect a heads-up notification; tapping opens the thread (`leman://thread/{id}`).
  5. Verify no notification for your own just-sent messages; verify force-stopping the app suppresses delivery (documented FCM behavior).
- [ ] **Step 4: Record findings** — note anything the separate server project must honor (esp. confirming/adjusting the `POST /api/devices` shape); update the gateway-contract memory with the assumed/verified contract.

---

## Notes for the implementer

- **Cold-process gotcha (critical):** a process started by FCM never runs `MainActivity`/`ConnectionManager.reconfigure()`. Both workers call `container.configurePushClient()` first; do not remove it or every call returns `NotConfigured`.
- **Why no dedup map (spec deviation):** the spec proposed a `notifiedActive` map. It is unnecessary — `SessionSyncer` only rebuilds threads whose `serverLastActive` changed and persists that value in the same transaction, so a duplicate/retried push finds the thread unchanged and collects nothing. Only `hasSeededSync` remains, guarding the first-ever-sync-is-a-push flood.
- **Expedited work on API 34:** `setExpedited` runs as an expedited job (JobScheduler quota); `getForegroundInfo()` is not invoked at minSdk 34, so it is intentionally not overridden. If minSdk ever drops below 31, add a `getForegroundInfo()` override.
- **Toggle-off** stops local posting immediately but leaves the token registered server-side; server-side de-registration is a concern for the separate server project.
- **Version pins** (`firebaseBom = 34.0.0`, `googleServices = 4.4.2`, `work = 2.10.1`) are floors; if the AGP 9 toolchain wants newer, bump to the latest stable rather than downgrading AGP.
```