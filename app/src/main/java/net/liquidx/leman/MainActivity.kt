package net.liquidx.leman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.liquidx.leman.ui.nav.LemanNavHost
import net.liquidx.leman.ui.theme.LemanTheme

// FragmentActivity (a ComponentActivity) so BiometricPrompt can attach.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LemanApp).container

        // Health probe on STARTED so the status row settles fast (spec 04).
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
                container.connectionManager.reconfigure()
                // The user can revoke POST_NOTIFICATIONS from system settings while
                // we're backgrounded; reflect the true state so the toggle can't read
                // ON while nothing is ever delivered.
                container.appScope.launch {
                    val revoked = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                    if (revoked && container.settings.settings.first().notificationsEnabled) {
                        container.settings.update { it.copy(notificationsEnabled = false) }
                    }
                }
            },
        )

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
    }

    /** `reveal` re-triggers BiometricPrompt when biometric unlock is on (spec 03). */
    private fun authenticateThen(onAuthed: () -> Unit) {
        val canAuth = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onAuthed() // no biometrics enrolled — don't lock the user out of their own key
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthed()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("reveal api key")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    private fun shareExport(json: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "leman-threads.json")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, "export threads"))
    }
}
