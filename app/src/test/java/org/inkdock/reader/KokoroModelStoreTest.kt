package org.inkdock.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
class KokoroModelStoreTest {
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private val bytes="synthetic model data".toByteArray()
    private fun spec(name:String="model.onnx")=KokoroModelStore.ModelFile(name,"https://huggingface.co/test/$name",bytes.size.toLong(),hash(bytes))
    private fun temp()=Files.createTempDirectory("inkdock-store-test").toFile()
    @Test fun installsOnlyVerifiedBytesAndDeletes(){ val root=temp();try{val s=KokoroModelStore(root,listOf(spec())){bytes.inputStream()};val p=mutableListOf<Long>();s.download{p+=it.bytes};assertTrue(s.isReady());assertEquals(bytes.size.toLong(),p.last());assertEquals(bytes.toList(),File(s.acquire(),"model.onnx").readBytes().toList());assertThrows(IllegalStateException::class.java){s.delete()};s.release();s.delete();assertFalse(s.isReady())}finally{root.deleteRecursively()} }
    @Test fun corruptPayloadNeverBecomesReady(){val root=temp();try{val s=KokoroModelStore(root,listOf(spec())){ByteArray(bytes.size).inputStream()};assertThrows(IllegalStateException::class.java){s.download{}};assertFalse(s.isReady());assertFalse(s.isDownloading());assertFalse(root.walk().any{it.name.endsWith(".part")})}finally{root.deleteRecursively()} }
    @Test fun truncatedPayloadRejected(){val root=temp();try{val s=KokoroModelStore(root,listOf(spec())){byteArrayOf(1).inputStream()};assertThrows(IllegalStateException::class.java){s.download{}};assertFalse(s.isReady())}finally{root.deleteRecursively()} }
    @Test fun cancellationAndRetry(){val root=temp();try{val s=KokoroModelStore(root,listOf(spec())){bytes.inputStream()};assertThrows(java.io.IOException::class.java){s.download{s.cancelDownload()}};assertFalse(s.isReady());s.download{};assertTrue(s.isReady())}finally{root.deleteRecursively()} }
    @Test fun detectsSameSizeTamperingBeforeLoad(){val root=temp();try{val s=KokoroModelStore(root,listOf(spec())){bytes.inputStream()};s.download{};File(s.directory,"model.onnx").writeBytes(ByteArray(bytes.size));assertThrows(IllegalStateException::class.java){s.acquire()};s.delete();assertFalse(s.isReady())}finally{root.deleteRecursively()} }
    @Test fun rejectsPathTraversal(){assertThrows(IllegalArgumentException::class.java){KokoroModelStore(temp(),listOf(spec("../escape"))){bytes.inputStream()}}}
    @Test fun retryReusesVerifiedCompletedFiles(){val root=temp();try{var fail=true;var calls=0;val specs=listOf(spec("first"),spec("second"));val s=KokoroModelStore(root,specs){url->calls++;if(url.endsWith("second")&&fail)throw java.io.IOException("offline");bytes.inputStream()};assertThrows(java.io.IOException::class.java){s.download{}};fail=false;s.download{};assertEquals(3,calls);assertTrue(s.isReady())}finally{root.deleteRecursively()} }
    @Test fun runningDownloadBlocksAcquireDeleteAndDuplicateDownload(){val root=temp();try{val entered=CountDownLatch(1);val release=CountDownLatch(1);val s=KokoroModelStore(root,listOf(spec())){entered.countDown();release.await();bytes.inputStream()};val t=thread{s.download{}};entered.await();assertThrows(IllegalStateException::class.java){s.acquire()};assertThrows(IllegalStateException::class.java){s.delete()};assertThrows(IllegalStateException::class.java){s.download{}};release.countDown();t.join();assertTrue(s.isReady())}finally{root.deleteRecursively()} }
}
