package org.inkdock.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only tests, isolated aliases/files. Uses synthetic tokens, never a provider API. */
@RunWith(AndroidJUnit4::class)
class CloudVoiceKeyStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var store: CloudVoiceKeyStore
    private val provider = CloudVoiceProvider.QWEN
    private val namespace = "-instrumentation"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = CloudVoiceKeyStore(context, namespace)
        CloudVoiceProvider.entries.forEach { store.remove(it) }
    }
    @After fun cleanup() { CloudVoiceProvider.entries.forEach { store.remove(it) } }

    @Test fun encryptedStorageIsOutsideBackupAndNoPlaintextIsPersisted() {
        val synthetic = "synthetic-not-a-real-provider-key".toCharArray()
        store.save(provider, synthetic)
        assertTrue(synthetic.all { it == '\u0000' })
        assertEquals(CloudVoiceKeyStatus.SAVED, store.status(provider))
        val file = File(context.noBackupFilesDir, "cloud-voice-keys$namespace/qwen.key")
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-not-a-real-provider-key"))
        val reopened = CloudVoiceKeyStore(context, namespace)
        var reference: CharArray? = null
        reopened.withKey(provider) { key ->
            assertTrue(key.contentEquals("synthetic-not-a-real-provider-key".toCharArray()))
            reference = key
        }
        assertTrue(reference!!.all { it == '\u0000' })
    }

    @Test fun randomizedEncryptionTamperDetectionAndProviderIsolation() {
        store.save(provider, "synthetic-value".toCharArray())
        val file = File(context.noBackupFilesDir, "cloud-voice-keys$namespace/qwen.key")
        val first = file.readBytes()
        store.save(provider, "synthetic-value".toCharArray())
        assertFalse(first.contentEquals(file.readBytes()))
        store.save(CloudVoiceProvider.MINIMAX, "synthetic-other".toCharArray())
        File(context.noBackupFilesDir, "cloud-voice-keys$namespace/minimax.key").writeBytes(file.readBytes())
        assertEquals(CloudVoiceKeyStatus.UNAVAILABLE, store.status(CloudVoiceProvider.MINIMAX))
        val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertEquals(CloudVoiceKeyStatus.UNAVAILABLE, store.status(provider))
    }

    @Test fun invalidReplacementDoesNotErasePreviousKeyAndRemoveClearsBothStores() {
        store.save(provider, "synthetic-old-value".toCharArray())
        val invalid = "has whitespace\n".toCharArray()
        try { store.save(provider, invalid); fail("Expected invalid key") }
        catch (error: CloudVoiceException) { assertEquals(CloudVoiceError.KEY_INVALID, error.error) }
        assertTrue(invalid.all { it == '\u0000' })
        assertEquals(CloudVoiceKeyStatus.SAVED, store.status(provider))
        store.remove(provider)
        assertEquals(CloudVoiceKeyStatus.MISSING, store.status(provider))
        assertFalse(File(context.noBackupFilesDir, "cloud-voice-keys$namespace/qwen.key").exists())
    }
}
