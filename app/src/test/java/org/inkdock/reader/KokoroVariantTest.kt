package org.inkdock.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
class KokoroVariantTest {
    @Test fun floatIsDefaultWithSeparatePinnedModel() {
        assertEquals("float",KokoroManifest.variant("unknown").id)
        assertEquals(381766704L,KokoroManifest.float.bytes)
        assertEquals(170433930L,KokoroManifest.int8.bytes)
        assertNotEquals(KokoroManifest.float.files.first().sha256,KokoroManifest.int8.files.first().sha256)
        assertNotEquals(KokoroManifest.float.modelName,KokoroManifest.int8.modelName)
        assertEquals(KokoroManifest.float.files.drop(1),KokoroManifest.int8.files.drop(1))
    }
    @Test fun variantsNeverOverwriteOrLoadConcurrently() {
        val root=Files.createTempDirectory("kokoro-variants").toFile()
        try {
            val bytes="same verified test data".toByteArray()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val specs=listOf(KokoroModelStore.ModelFile("model.onnx","https://example.com/model",bytes.size.toLong(),hash))
            val lease=AtomicBoolean(false)
            val a=KokoroModelStore(root,specs,"float",lease){bytes.inputStream()}
            val b=KokoroModelStore(root,specs,"int8",lease){bytes.inputStream()}
            a.download{};b.download{}
            assertNotEquals(a.directory,b.directory)
            a.acquire();assertThrows(IllegalStateException::class.java){b.acquire()}
            assertThrows(IllegalStateException::class.java){b.download{}}
            a.release();b.acquire();b.release();a.delete()
            assertFalse(a.isReady());assertTrue(b.isReady())
            assertEquals(bytes.toList(),File(b.acquire(),"model.onnx").readBytes().toList());b.release()
        }finally{root.deleteRecursively()}
    }
}
