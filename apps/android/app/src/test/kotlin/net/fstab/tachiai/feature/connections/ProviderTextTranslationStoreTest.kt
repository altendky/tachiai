package net.fstab.tachiai.feature.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.ProviderSourceLanguage
import net.fstab.tachiai.presentation.ProviderTextTranslationSettings
import net.fstab.tachiai.presentation.PrototypeService
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
        val saved = store.update { it.copy(sourceLanguages = mapOf(id to ProviderSourceLanguage.AUTO), providerTextInstances = setOf(id)) }
        assertEquals(saved, store.read())
        assertTrue(store.read().providerText(id))
        assertEquals(1, memory.writes)
    }

    @Test fun failedWriteKeepsPreviousRecord() {
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        store.update { it.copy(providerTextInstances = setOf(id)) }
        memory.failWrite = true
        assertThrows(IOException::class.java) { store.update { it.copy(providerTextInstances = emptySet()) } }
        assertTrue(store.read().providerText(id))
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
        listOf(encoded(version = 2), encoded(version = 3), encoded(version = 4), encoded(tag = "xx"), encoded(duplicate = true),
            encoded(trailing = true)).forEach {
            memory.bytes = it
            assertThrows(Exception::class.java) { store.read() }
        }
    }

    @Test fun globalSwitchRecordsReadCleanlyWithEveryProviderOff() {
        // Versions 1 and 2 (#643, #664) held one global Translate provider text
        // switch. It is dropped (#765): every provider starts off and the other
        // choices are kept.
        val other = "12345678-1234-1234-1234-123456789abd"
        fun global(version: Int) = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeBoolean(true); data.writeInt(1); data.writeUTF(id); data.writeUTF("ko")
            if (version == 2) { data.writeInt(1); data.writeUTF(other) }
        } }.toByteArray()
        listOf(1, 2).forEach { version ->
            val memory = Memory(); val store = ProviderTextTranslationStore(memory)
            memory.bytes = global(version)
            val read = store.read()
            assertEquals(emptySet<String>(), read.providerTextInstances)
            assertFalse(read.providerText(id)); assertFalse(read.providerText(other))
            assertEquals(mapOf(id to ProviderSourceLanguage("ko")), read.sourceLanguages)
            assertEquals(if (version == 2) setOf(other) else emptySet<String>(), read.frameTextInstances)
            assertEquals(0, memory.writes)
            // The next change rewrites the record in the current version without the global switch.
            store.update { it.withProviderText(ProviderInstance(other, PrototypeService.ABEMA, "ABEMA"), true, setOf(id, other)) }
            assertEquals(3, DataInputStream(ByteArrayInputStream(memory.bytes)).readInt())
            assertEquals(setOf(other), store.read().providerTextInstances)
            assertEquals(read.sourceLanguages, store.read().sourceLanguages)
        }
    }

    @Test fun textInVideoChoicesRoundTripAndMalformedListsAreRejected() {
        val memory = Memory(); val store = ProviderTextTranslationStore(memory)
        val saved = store.update { it.copy(frameTextInstances = setOf(id)) }
        assertEquals(saved, store.read())
        assertTrue(store.read().frameText(id))
        assertFalse(store.read().providerText(id))
        fun encoded(vararg ids: String) = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(2); data.writeBoolean(false); data.writeInt(0)
            data.writeInt(ids.size); ids.forEach(data::writeUTF)
        } }.toByteArray()
        fun current(frame: List<String>, providerText: List<String>?) = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(3); data.writeInt(0)
                data.writeInt(frame.size); frame.forEach(data::writeUTF)
                providerText?.let { data.writeInt(it.size); it.forEach(data::writeUTF) }
            } }.toByteArray()
        memory.bytes = current(listOf(id), listOf(id))
        assertTrue(store.read().frameText(id)); assertTrue(store.read().providerText(id))
        // Version 3 has no global switch and requires its provider-text list.
        listOf(encoded(id, id), encoded("not-an-instance"), current(listOf(id), null), current(emptyList(), listOf(id, id)),
            current(emptyList(), listOf("not-an-instance"))).forEach {
            memory.bytes = it
            assertThrows(Exception::class.java) { store.read() }
        }
    }
}
