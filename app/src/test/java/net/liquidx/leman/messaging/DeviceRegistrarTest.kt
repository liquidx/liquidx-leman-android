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
        disableAutoInitCalls: MutableList<Unit> = mutableListOf(),
        deleteTokenCalls: MutableList<Unit> = mutableListOf(),
    ): Pair<DeviceRegistrar, SettingsStore> {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "reg-${System.nanoTime()}",
        )
        val settings = SettingsStore(scope) { File(dir, "settings.preferences_pb") }
        val push = PushPrefsStore(scope) { File(dir, "push.preferences_pb") }
        // no-op auto-init + token delete: no Firebase app is initialized under Robolectric
        return DeviceRegistrar(
            client, settings, FakeApiKeyStore(key), push,
            enableAutoInit = {},
            disableAutoInit = { disableAutoInitCalls += Unit },
            deleteToken = { deleteTokenCalls += Unit },
        ) { token } to settings
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

    @Test
    fun unregister_ok_isDoneAndDisablesLocally() = runTest {
        val client = FakeHermesClient()
        val disableCalls = mutableListOf<Unit>()
        val deleteCalls = mutableListOf<Unit>()
        val (reg, _) = registrar(
            this, client, disableAutoInitCalls = disableCalls, deleteTokenCalls = deleteCalls,
        )
        assertEquals(DeviceRegistrar.Outcome.DONE, reg.unregister())
        assertEquals(1, client.unregisterDeviceCalls.size)
        assertEquals(1, disableCalls.size)
        assertEquals(1, deleteCalls.size)
    }

    @Test
    fun unregister_missingEndpoint404_isDoneNotError() = runTest {
        val client = FakeHermesClient().apply {
            unregisterDeviceResult = ApiResult.Err(ApiError.Client(404, "no route"))
        }
        val disableCalls = mutableListOf<Unit>()
        val deleteCalls = mutableListOf<Unit>()
        val (reg, _) = registrar(
            this, client, disableAutoInitCalls = disableCalls, deleteTokenCalls = deleteCalls,
        )
        assertEquals(DeviceRegistrar.Outcome.DONE, reg.unregister())
        assertEquals(1, disableCalls.size)
        assertEquals(1, deleteCalls.size)
    }

    @Test
    fun unregister_networkError_retriesLaterButStillDisablesLocally() = runTest {
        val client = FakeHermesClient().apply {
            unregisterDeviceResult = ApiResult.Err(ApiError.Network(IOException("down")))
        }
        val disableCalls = mutableListOf<Unit>()
        val deleteCalls = mutableListOf<Unit>()
        val (reg, _) = registrar(
            this, client, disableAutoInitCalls = disableCalls, deleteTokenCalls = deleteCalls,
        )
        assertEquals(DeviceRegistrar.Outcome.RETRY_LATER, reg.unregister())
        assertEquals(1, disableCalls.size)
        assertEquals(1, deleteCalls.size)
    }

    @Test
    fun unregister_otherClientError_givesUpButStillDisablesLocally() = runTest {
        val client = FakeHermesClient().apply {
            unregisterDeviceResult = ApiResult.Err(ApiError.Client(400, "bad request"))
        }
        val disableCalls = mutableListOf<Unit>()
        val deleteCalls = mutableListOf<Unit>()
        val (reg, _) = registrar(
            this, client, disableAutoInitCalls = disableCalls, deleteTokenCalls = deleteCalls,
        )
        assertEquals(DeviceRegistrar.Outcome.GAVE_UP, reg.unregister())
        assertEquals(1, disableCalls.size)
        assertEquals(1, deleteCalls.size)
    }
}
