package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderSourceLanguage
import net.fstab.tachiai.presentation.ProviderTextTranslationSettings
import org.junit.Assert.*
import org.junit.Test

class ProviderTextTranslationStoreTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("Synthetic private storage detail")
            bytes = plaintext.copyOf(); writes++
        }
    }
    private val id = "12345678-1234-1234-1234-123456789abc"

    @Test fun missingRecordIsOffAndRoundTripKeepsChoices() {
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        assertEquals(ProviderTextTranslationSettings(), store.read()); assertEquals(0, memory.writes)
        val saved = store.update { it.copy(enabled = true, sourceLanguages = mapOf(id to ProviderSourceLanguage.AUTO)) }
        assertEquals(saved, store.read())
        assertTrue(store.read().enabled)
        assertEquals(1, memory.writes)
    }

    @Test fun failedWriteKeepsPreviousRecord() {
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        store.update { it.copy(enabled = true) }
        memory.failWrite = true
        assertThrows(IOException::class.java) { store.update { it.copy(enabled = false) } }
        assertTrue(store.read().enabled)
    }

    @Test fun malformedRecordsAreRejected() {
        fun encoded(version: Int = 1, tag: String = "ja", duplicate: Boolean = false, trailing: Boolean = false) =
            ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
                data.writeInt(version); data.writeBoolean(true); data.writeInt(if (duplicate) 2 else 1)
                repeat(if (duplicate) 2 else 1) { data.writeUTF(id); data.writeUTF(tag) }
                if (trailing) data.writeByte(0)
            } }.toByteArray()
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        memory.bytes = encoded()
        assertEquals(ProviderSourceLanguage("ja"), store.read().sourceLanguages[id])
        // Version 1 records (before #664) read with no text-in-video choices; a
        // version 2 record without its text-in-video list is truncated.
        assertEquals(emptySet<String>(), store.read().frameTextInstances)
        listOf(encoded(version = 2), encoded(version = 3), encoded(tag = "xx"), encoded(duplicate = true), encoded(trailing = true)).forEach {
            memory.bytes = it
            assertThrows(Exception::class.java) { store.read() }
        }
    }

    @Test fun textInVideoChoicesRoundTripAndMalformedListsAreRejected() {
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        val saved = store.update { it.copy(frameTextInstances = setOf(id)) }
        assertEquals(saved, store.read())
        assertTrue(store.read().frameText(id))
        assertFalse(store.read().enabled)
        fun encoded(vararg ids: String) = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(2); data.writeBoolean(false); data.writeInt(0)
            data.writeInt(ids.size); ids.forEach(data::writeUTF)
        } }.toByteArray()
        listOf(encoded(id, id), encoded("not-an-instance")).forEach {
            memory.bytes = it
            assertThrows(Exception::class.java) { store.read() }
        }
    }
}
