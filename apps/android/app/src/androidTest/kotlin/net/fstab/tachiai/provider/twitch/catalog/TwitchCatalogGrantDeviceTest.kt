package net.fstab.tachiai.provider.twitch.catalog

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.twitchCatalogInstanceBindingName
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Synthetic isolated UUID on an explicitly selected disposable emulator only.
// No provider instance, actual grant, route or account is read or replaced.
class TwitchCatalogGrantDeviceTest {
    @Test fun androidOAuthJsonDecodesIntoStrictCatalogCredentialsAndValidation() {
        // Exercise Android's actual JSON implementation, rather than a JVM
        // injected decoder, after the compiler's default-callback failure.
        val decoder = Class.forName("net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogAuthTransportKt")
            .getDeclaredMethod("catalogResponseFields", String::class.java).apply { isAccessible = true }
        fun response(json: JSONObject): DeviceAuthResponse {
            @Suppress("UNCHECKED_CAST")
            val fields = decoder.invoke(null, json.toString()) as Map<String, Any?>
            return DeviceAuthResponse(200, fields)
        }
        val token = response(JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
            .put("token_type", "bearer").put("scope", JSONArray().put(TWITCH_CATALOG_SCOPE)).put("expires_in", 3600))
        val credentials = parseTwitchCatalogToken(token)
        assertEquals("fixture-access", credentials.accessToken)
        val validation = parseTwitchCatalogValidation(response(JSONObject().put("client_id", SMART_TV_TWITCH_CLIENT_ID)
            .put("user_id", "fixture-user").put("scopes", JSONArray().put(TWITCH_CATALOG_SCOPE)).put("expires_in", 3600)))
        assertEquals("fixture-user", validation.userId)
        assertEquals(setOf(TWITCH_CATALOG_SCOPE), validation.scopes)
    }

    @Test fun failedEncryptedClearPersistsIntentAndLocalRetryNeedsNoProviderOrRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val binding = twitchCatalogInstanceBindingName(id)
        val lock = File(context.noBackupFilesDir, "twitch-catalog-$id.lock")
        val marker = File(context.noBackupFilesDir, "${lock.name}.forget-pending")
        val actual = AndroidPrivateSecretStore.twitchCatalogInstance(context, id)
        var failWrite = false
        val failing = object : PrivateSecretStore {
            override fun read() = actual.read()
            override fun write(plaintext: ByteArray) {
                if (failWrite) error("synthetic keystore write failure")
                actual.write(plaintext)
            }
        }
        try {
            val store = TwitchCatalogGrantStore(failing, id, lock, wallMs = { 1000L })
            val attempt = store.beginConnection()
            val credentials = TwitchCatalogCredentials("fixture-access", "fixture-refresh", 60_000)
            val validation = TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), 60_000)
            assertNotNull(store.commitConnection(attempt, TwitchCatalogReplacement(credentials, validation, 60_000)))
            assertNotNull(actual.read())
            failWrite = true
            assertThrows(TwitchCatalogStoreException::class.java) { store.forget() }
            assertTrue(marker.exists())
            assertEquals(4L, marker.length())
            assertThrows(TwitchCatalogStoreException::class.java) {
                TwitchCatalogGrantStore(actual, id, lock).read()
            }
            // Android helper uses only the immutable catalog binding and lock.
            val cleared = forgetAndroidTwitchCatalogConnection(context, id)
            assertEquals(TwitchCatalogSessionState.MISSING, cleared.summary.state)
            assertFalse(marker.exists())
            assertEquals(TwitchCatalogGrantState.CLEARED, TwitchCatalogGrantStore(actual, id, lock).read()?.state)
        } finally {
            File(context.noBackupFilesDir, "$binding.enc").delete()
            File(context.noBackupFilesDir, "$binding.enc.bak").delete()
            marker.delete(); lock.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("tachiai.$binding.v1") }
        }
    }
}
