package net.liquidx.leman.ui.config

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import net.liquidx.leman.testutil.type
import net.liquidx.leman.ui.threads.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun kotlinx.coroutines.test.TestScope.vm(h: VmHarness, allowHttp: Boolean = false) =
        ConfigViewModel(
            settingsStore = h.settingsStore,
            apiKeyStore = h.apiKeyStore,
            connectionManager = h.connectionManager,
            repo = h.repo,
            allowHttp = allowHttp,
        )

    @Test
    fun saveServerUrl_rejectsMalformed_acceptsHttpsOrigin() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }

            vm.serverUrlState.type("not a url")
            vm.onEvent(ConfigEvent.SaveServerUrl)
            assertNotNull(awaitUntil { it.urlError != null }.urlError)

            vm.serverUrlState.type("http://insecure.example")
            advanceUntilIdle() // editing clears the previous error first
            vm.onEvent(ConfigEvent.SaveServerUrl)
            assertNotNull(awaitUntil { it.urlError != null }.urlError) // http rejected in release

            vm.serverUrlState.type("https://gw.example/")
            vm.onEvent(ConfigEvent.SaveServerUrl)
            val saved = awaitUntil { it.settings.serverUrl == "https://gw.example" }
            assertNull(saved.urlError)
            // the field reflects the normalized origin after save
            advanceUntilIdle()
            assertEquals("https://gw.example", vm.serverUrlState.text.toString())
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun saveServerUrl_typingClearsValidationError() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.serverUrlState.type("not a url")
            vm.onEvent(ConfigEvent.SaveServerUrl)
            assertNotNull(awaitUntil { it.urlError != null }.urlError)

            vm.serverUrlState.type("not a url, but edited")
            assertNull(awaitUntil { it.urlError == null }.urlError)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun saveServerUrl_allowsHttpInDebug() = runTest {
        val h = VmHarness(this)
        val vm = vm(h, allowHttp = true)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.serverUrlState.type("http://10.0.2.2:8080")
            vm.onEvent(ConfigEvent.SaveServerUrl)
            awaitUntil { it.settings.serverUrl == "http://10.0.2.2:8080" }
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun testConnection_transitionsThroughTestingToOk() = runTest {
        val h = VmHarness(this)
        h.apiKeyStore.set("key")
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.TestConnection)
            assertEquals(TestConnectionState.Testing, awaitUntil { it.testResult is TestConnectionState.Testing }.testResult)
            val ok = awaitUntil { it.testResult is TestConnectionState.Ok }.testResult as TestConnectionState.Ok
            assertEquals("hermes-agent", ok.platform)
            assertEquals("0.18.0", ok.version)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun testConnection_authFailure_reportsAuthFailed() = runTest {
        val h = VmHarness(this)
        h.client.healthResult = ApiResult.Err(ApiError.Auth(401))
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.TestConnection)
            awaitUntil { it.testResult is TestConnectionState.AuthFailed }
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun glyphSelect_singleSelect_ignoresUnknownGlyph() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.SelectGlyph("⌬"))
            assertEquals("⌬", awaitUntil { it.settings.agentGlyph == "⌬" }.settings.agentGlyph)
            vm.onEvent(ConfigEvent.SelectGlyph("☠"))
            advanceUntilIdle()
            assertEquals("⌬", vm.state.value.settings.agentGlyph)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun agentName_seedsFieldFromStoredSettingsOnce() = runTest {
        val h = VmHarness(this)
        h.settingsStore.update { it.copy(agentName = "zephyr") }
        advanceUntilIdle()
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            advanceUntilIdle()
            assertEquals("zephyr", vm.agentNameState.text.toString())
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun agentName_editsPersist_withoutFieldEverBeingRewritten() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            advanceUntilIdle() // seeding done
            // simulate fast typing: two edits before persistence catches up
            vm.agentNameState.type("jun")
            vm.agentNameState.type("juno II")
            awaitUntil { it.settings.agentName == "juno II" }
            // late DataStore emissions must never revert the field text
            advanceUntilIdle()
            assertEquals("juno II", vm.agentNameState.text.toString())
            assertEquals("juno II", vm.state.value.settings.agentName)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun saveApiKey_storesMasksAndReconfigures() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.apiKeyState.type("hm_0123456789abcdef3kf2")
            vm.onEvent(ConfigEvent.SaveApiKey)
            val masked = awaitUntil { it.apiKeyMasked != null }.apiKeyMasked
            assertEquals("hm_" + "••••••••••••" + "3kf2", masked)
            assertEquals("hm_0123456789abcdef3kf2", h.apiKeyStore.get())
            assertEquals("", vm.apiKeyState.text.toString()) // input cleared after save
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun clearAllThreads_requiresSecondTapWithinWindow() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 0.5), RunEvent.RunCompleted("x", null, 2.0)),
        )
        h.repo.createThread("to be cleared")
        advanceUntilIdle()

        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.ClearAllThreads) // arms
            assertTrue(awaitUntil { it.confirmClearArmed }.confirmClearArmed)
            assertEquals(1, h.db.threadDao().threadCount()) // nothing wiped yet

            vm.onEvent(ConfigEvent.ClearAllThreads) // confirms
            awaitUntil { !it.confirmClearArmed }
            advanceUntilIdle()
            assertEquals(0, h.db.threadDao().threadCount())
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun clearAllThreads_armTimesOutAfter3s() = runTest {
        val h = VmHarness(this)
        val vm = vm(h)
        vm.state.test {
            awaitUntil { it.loaded }
            vm.onEvent(ConfigEvent.ClearAllThreads)
            assertTrue(awaitUntil { it.confirmClearArmed }.confirmClearArmed)
            advanceTimeBy(3_500)
            assertFalse(awaitUntil { !it.confirmClearArmed }.confirmClearArmed)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

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
}
