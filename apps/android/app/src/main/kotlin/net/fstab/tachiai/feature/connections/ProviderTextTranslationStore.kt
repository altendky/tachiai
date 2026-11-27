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

// Worker-only record of explicit per-instance Source language choices, the
// instances whose Translate text in video default is on (#664) and the
// instances whose Translate provider text is on (#765, version 3). It holds no
// provider text, recognized text, translations, frames or account data.
// Versions 1 and 2 held one global Translate provider text switch; it is read
// and dropped, so every provider starts off after the update (#765).
internal class ProviderTextTranslationStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object {
        private val lock = Any()
        private const val VERSION = 3; private const val VERSION_GLOBAL_SWITCH = 2; private const val VERSION_LANGUAGES_ONLY = 1
    }
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
            check(version == VERSION || version == VERSION_GLOBAL_SWITCH || version == VERSION_LANGUAGES_ONLY)
            // The former global switch is not carried over (#765).
            if (version != VERSION) input.readBoolean()
            val count = input.readInt(); check(count in 0..MAX_TRANSLATION_SETTINGS_ENTRIES)
            val languages = linkedMapOf<String, ProviderSourceLanguage>()
            repeat(count) {
                val id = input.readUTF(); val tag = input.readUTF()
                check(id !in languages)
                languages[id] = ProviderSourceLanguage(tag)
            }
            fun instances(): Set<String> = linkedSetOf<String>().also { ids ->
                val count = input.readInt(); check(count in 0..MAX_TRANSLATION_SETTINGS_ENTRIES)
                repeat(count) { check(ids.add(input.readUTF())) }
            }
            val frameText = if (version == VERSION_LANGUAGES_ONLY) emptySet() else instances()
            val providerText = if (version == VERSION) instances() else emptySet()
            check(input.read() == -1)
            ProviderTextTranslationSettings(languages, frameText, providerText)
        }
    }

    fun update(transform: (ProviderTextTranslationSettings) -> ProviderTextTranslationSettings): ProviderTextTranslationSettings = locked {
        val next = transform(readUnlocked())
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(VERSION); data.writeInt(next.sourceLanguages.size)
            next.sourceLanguages.forEach { (id, language) -> data.writeUTF(id); data.writeUTF(language.tag) }
            data.writeInt(next.frameTextInstances.size)
            next.frameTextInstances.forEach(data::writeUTF)
            data.writeInt(next.providerTextInstances.size)
            next.providerTextInstances.forEach(data::writeUTF)
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
        next
    }
}
