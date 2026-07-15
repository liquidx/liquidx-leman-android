package net.liquidx.leman.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Storage for the Hermes bearer token (spec 03): AES-256-GCM key in
 * AndroidKeystore; ciphertext + IV in a DataStore file excluded from Auto
 * Backup / device transfer. A Keystore key invalidated by biometric
 * re-enrollment is treated as a missing key.
 */
interface ApiKeyStore {
    suspend fun get(): String?
    suspend fun set(value: String)
    suspend fun clear()
}

class KeystoreApiKeyStore(
    context: Context,
    scope: CoroutineScope,
) : ApiKeyStore {

    private object Keys {
        val ciphertext = stringPreferencesKey("ciphertext")
        val iv = stringPreferencesKey("iv")
    }

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        // File name matters: excluded in backup_rules.xml / data_extraction_rules.xml.
        File(context.filesDir, "datastore/apikey.preferences_pb")
    }

    override suspend fun get(): String? {
        val prefs = store.data.first()
        val ciphertext = prefs[Keys.ciphertext] ?: return null
        val iv = prefs[Keys.iv] ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear() // biometrics re-enrolled → key gone; surface as not configured (spec 03)
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun set(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        store.edit { p ->
            p[Keys.ciphertext] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            p[Keys.iv] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "leman.apikey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
