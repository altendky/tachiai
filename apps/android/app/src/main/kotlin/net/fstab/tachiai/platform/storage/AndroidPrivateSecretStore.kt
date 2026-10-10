package net.fstab.tachiai.platform.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// No export/backup, SharedPreferences, plaintext fallback, password or browser
// session access. All key/file I/O is invoked from the worker, never Compose.
internal class AndroidPrivateSecretStore private constructor(context: Context, bindingName: String) : PrivateSecretStore {
    constructor(context: Context, slot: PrivateAuthorizationSlot) : this(context, slot.bindingName)
    companion object {
        // Worker-only retirement of the removed app registration. Do not read,
        // decrypt or convert its grant, or touch any provider/Smart TV binding.
        fun removeRetiredTwitchRegistration(context: Context) {
            val bindingName = "twitch-own-authorization"
            val base = File(context.noBackupFilesDir, "$bindingName.enc")
            AtomicFile(base).delete()
            val siblings = listOf(base, File(base.path + ".bak"), File(base.path + ".new"))
            siblings.forEach { if (it.exists()) check(it.delete()) }
            check(siblings.none { it.exists() })
            val alias = "tachiai.$bindingName.v1"
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
            check(!keys.containsAlias(alias))
        }

        fun twitchProviderInstance(context: Context, id: String): AndroidPrivateSecretStore {
            return AndroidPrivateSecretStore(context, twitchProviderInstanceBindingName(id))
        }
        fun configuredProviderInstance(context: Context, id: String): AndroidPrivateSecretStore {
            return AndroidPrivateSecretStore(context, configuredProviderInstanceBindingName(id))
        }
        fun twitchCatalogInstance(context: Context, id: String): AndroidPrivateSecretStore {
            return AndroidPrivateSecretStore(context, twitchCatalogInstanceBindingName(id))
        }
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$bindingName.enc"))
    private val alias = "tachiai.$bindingName.v1"
    private val binding = "${context.packageName}:$bindingName:v1".toByteArray()

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) // Never replace a missing key to disguise unreadable data.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    override fun read(): ByteArray? {
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            // openRead first allows AtomicFile's backup recovery. A remaining
            // base/backup or unreadable directory is not an absent credential.
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                file.baseFile.parentFile?.canRead() == true) return null
            throw error
        }
        val bytes = input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(output.size() + count <= PRIVATE_SECRET_LIMIT + 29)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decryptPrivateSecret(key(create = false), binding, bytes)
    }

    override fun write(plaintext: ByteArray) {
        val encrypted = encryptPrivateSecret(key(create = true), binding, plaintext)
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error // Caller reports a fixed category, never exception text.
        }
    }
}
