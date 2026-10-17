package org.inkdock.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
class KokoroDownloadLifecycleTest {
    private val data="verified data".toByteArray()
    private fun spec(name:String)=KokoroModelStore.ModelFile(name,"https://example.com/$name",data.size.toLong(),MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)})
    @Test fun cancellationBeforeWorkerStartsNeverOpensNetwork() {
        val root=Files.createTempDirectory("cancel-queued").toFile()
        try {
            var calls=0
            val store=KokoroModelStore(root,listOf(spec("model"))){calls++;data.inputStream()}
            val token=store.prepareDownload();store.cancelDownload()
            assertTrue(store.isDownloading())
            assertThrows(IOException::class.java){store.download(token){}}
            assertEquals(0,calls);assertFalse(store.isDownloading());assertFalse(store.isReady())
            store.download{};assertTrue(store.isReady());assertEquals(1,calls)
        }finally{root.deleteRecursively()}
    }
    @Test fun retrySpaceUsesRemainingAndReportsStaging() {
        val root=Files.createTempDirectory("retry-space").toFile()
        try {
            var fail=true;var free=Long.MAX_VALUE
            val store=KokoroModelStore(root,listOf(spec("first"),spec("second")),freeSpace={free}) {url->if(url.endsWith("second")&&fail)throw IOException("network");data.inputStream()}
            assertThrows(IOException::class.java){store.download{}}
            assertEquals(data.size.toLong(),store.stagedBytes());assertEquals(data.size.toLong(),store.storedBytes())
            free=64L*1024*1024+data.size;fail=false;store.download{}
            assertTrue(store.isReady());assertEquals(0L,store.stagedBytes())
            store.delete();assertEquals(0L,store.storedBytes())
        }finally{root.deleteRecursively()}
    }
    private class Connection(val code:Int,val location:String?=null,val fail:Boolean=false):HttpURLConnection(URL("https://example.com")) {
        var disconnected=false
        override fun disconnect(){disconnected=true}
        override fun connect(){}
        override fun usingProxy()=false
        override fun getResponseCode():Int {if(fail)throw IOException("connect failed");return code}
        override fun getHeaderField(name:String)=location
        override fun getInputStream()="data".byteInputStream()
    }
    @Test fun failedAndMalformedRedirectConnectionsDisconnect() {
        for(c in listOf(Connection(302),Connection(500),Connection(200,fail=true))) {
            assertThrows(IOException::class.java){KokoroModelStore.openModelHttps("https://example.com",KokoroModelStore.DownloadTicket()){c}}
            assertTrue(c.disconnected)
        }
    }
    @Test fun cancellationPreventsNextRedirectAndSuccessCloses() {
        val token=KokoroModelStore.DownloadTicket();val first=Connection(302,"https://example.com/next");var calls=0
        assertThrows(IOException::class.java){KokoroModelStore.openModelHttps("https://example.com",token){calls++;token.cancelled.set(true);first}}
        assertEquals(1,calls);assertTrue(first.disconnected)
        val ok=Connection(200);val stream=KokoroModelStore.openModelHttps("https://example.com",KokoroModelStore.DownloadTicket()){ok}
        assertFalse(ok.disconnected);stream.close();assertTrue(ok.disconnected)
    }
    @Test fun nestedLicenseDirectoriesAreNeverOpenedAsFiles() {
        val tree=mapOf("root" to listOf("dep","LICENSE"),"root/dep" to listOf("LICENSES"),"root/dep/LICENSES" to listOf("MIT.txt"))
        val opened=mutableListOf<String>()
        val result=KokoroLicenseText.collect("root",{tree[it].orEmpty()},{assertFalse(tree.containsKey(it));opened+=it;"license text"})
        assertEquals(listOf("root/LICENSE","root/dep/LICENSES/MIT.txt"),opened)
        assertTrue(result.contains("MIT.txt"))
    }
}
