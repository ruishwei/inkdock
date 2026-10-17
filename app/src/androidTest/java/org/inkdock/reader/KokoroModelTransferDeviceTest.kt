package org.inkdock.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Real app-private Android filesystem, synthetic in-memory transfers only. Never opens a network. */
@RunWith(AndroidJUnit4::class)
class KokoroModelTransferDeviceTest {
    private val data = "SYNTHETIC MODEL TRANSFER FIXTURE".toByteArray()
    private fun spec(name: String) = KokoroModelStore.ModelFile(name, "https://example.invalid/$name", data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })

    @Test fun cancelBeforeWorkerCannotPublishOrOpenTransferAndRetryIsSafe() = withRoot { root ->
        val opens = AtomicInteger()
        val store = KokoroModelStore(root, listOf(spec("model.bin")), "synthetic-cancel", open = {
            opens.incrementAndGet(); data.inputStream()
        })
        val queued = store.prepareDownload()
        store.cancelDownload()
        expectFailure { store.download(queued) {} }
        assertEquals(0, opens.get())
        assertFalse(store.isDownloading())
        assertFalse(store.isReady())
        assertFalse(File(store.directory, "VERIFIED").exists())
        store.download {}
        assertTrue(store.isReady())
        assertEquals(1, opens.get())
        assertEquals(store.directory, store.acquire())
        store.release()
        store.delete()
        assertEquals(0L, store.storedBytes())
    }

    @Test fun cancelDuringSecondFileKeepsOnlyVerifiedStagingAndRetryReusesFirstFile() = withRoot { root ->
        val opens = mutableListOf<String>()
        val store = KokoroModelStore(root, listOf(spec("first.bin"), spec("second.bin")), "synthetic-staging", open = { url ->
            opens += url.substringAfterLast('/'); data.inputStream()
        })
        expectFailure {
            store.download { progress -> if (progress.file == "second.bin") store.cancelDownload() }
        }
        assertFalse(store.isReady())
        assertFalse(store.isDownloading())
        assertEquals(data.size.toLong(), store.stagedBytes())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") || it.name == "VERIFIED" })
        store.download {}
        assertEquals(listOf("first.bin", "second.bin", "second.bin"), opens)
        assertTrue(store.isReady())
        assertEquals(0L, store.stagedBytes())
        assertEquals(store.directory, store.acquire())
        store.release()
        // Same-sized corruption cannot pass acquire even with a pre-existing VERIFIED marker.
        File(store.directory, "first.bin").writeBytes(ByteArray(data.size) { 7 })
        expectFailure { store.acquire() }
        store.delete()
        assertEquals(0L, store.storedBytes())
    }

    @Test fun wrongDigestNeverPromotesAStagingDirectory() = withRoot { root ->
        val opens = AtomicInteger()
        val store = KokoroModelStore(root, listOf(spec("model.bin")), "synthetic-bad-hash",
            open = { opens.incrementAndGet(); ByteArray(data.size).inputStream() })
        expectFailure { store.download {} }
        assertFalse(store.isReady())
        assertFalse(store.directory.exists())
        assertEquals("Wrong-hash data reached the verifier", 1, opens.get())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") || it.name == "VERIFIED" })
        assertFalse(store.isDownloading())
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected transfer/verification failure") }
        catch (_: IOException) { }
        catch (_: IllegalStateException) { }
    }
    private fun withRoot(test: (File) -> Unit) {
        val root = File(KokoroDeviceTestSupport.context.cacheDir, "synthetic-transfer-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        try { test(root) } finally { assertTrue(root.deleteRecursively()) }
    }
}
