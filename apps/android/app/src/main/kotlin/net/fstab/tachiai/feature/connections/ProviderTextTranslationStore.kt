package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.processFileLocked
import net.fstab.tachiai.presentation.MAX_TRANSLATION_SETTINGS_ENTRIES
import net.fstab.tachiai.presentation.ProviderSourceLanguage
import net.fstab.tachiai.presentation.ProviderTextTranslationSettings

internal fun providerTextTranslationStore(context: Context) = ProviderTextTranslationStore(
    AndroidPrivateSecretStore(context.applicationContext, PrivateAuthorizationSlot.PROVIDER_TEXT_TRANSLATION),
    File(context.applicationContext.noBackupFilesDir, "provider-text-translation.lock"),
)

// Worker-only record of the on/off switch, explicit per-instance Source
// language choices and the instances whose Translate text in video default is
// on (#664, version 2). It holds no provider text, recognized text,
// translations, frames or account data.
internal class ProviderTextTranslationStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any(); private const val VERSION = 2; private const val VERSION_LANGUAGES_ONLY = 1 }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else processFileLocked(file, action)
    }

    fun read(): ProviderTextTranslationSettings = locked(::readUnlocked)

    private fun readUnlocked(): ProviderTextTranslationSettings {
        val bytes = store.read() ?: return ProviderTextTranslationSettings()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            check(version == VERSION || version == VERSION_LANGUAGES_ONLY)
            val enabled = input.readBoolean()
            val count = input.readInt(); check(count in 0..MAX_TRANSLATION_SETTINGS_ENTRIES)
            val languages = linkedMapOf<String, ProviderSourceLanguage>()
            repeat(count) {
                val id = input.readUTF(); val tag = input.readUTF()
                check(id !in languages)
                languages[id] = ProviderSourceLanguage(tag)
            }
            val frameText = linkedSetOf<String>()
            if (version == VERSION) {
                val frameCount = input.readInt(); check(frameCount in 0..MAX_TRANSLATION_SETTINGS_ENTRIES)
                repeat(frameCount) { check(frameText.add(input.readUTF())) }
            }
            check(input.read() == -1)
            ProviderTextTranslationSettings(enabled, languages, frameText)
        }
    }

    fun update(transform: (ProviderTextTranslationSettings) -> ProviderTextTranslationSettings): ProviderTextTranslationSettings = locked {
        val next = transform(readUnlocked())
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(VERSION); data.writeBoolean(next.enabled); data.writeInt(next.sourceLanguages.size)
            next.sourceLanguages.forEach { (id, language) -> data.writeUTF(id); data.writeUTF(language.tag) }
            data.writeInt(next.frameTextInstances.size)
            next.frameTextInstances.forEach(data::writeUTF)
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
        next
    }
}
