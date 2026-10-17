package org.inkdock.reader

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** App-private, fixed-manifest model store. Network is used only after a user's download action. */
class KokoroModelStore(private val root: File, private val specs: List<ModelFile>,
    private val modelKey: String = "kokoro-zh-v1_1-int8",
    private val using: AtomicBoolean = AtomicBoolean(false),
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val open: ((String) -> InputStream)? = null) {
    data class ModelFile(val path: String, val url: String, val size: Long, val sha256: String)
    data class Progress(val bytes: Long, val total: Long, val file: String)
    val totalBytes = specs.sumOf { it.size }
    val directory = File(root, modelKey)
    private val staging = File(root, ".$modelKey-staging")
    class DownloadTicket internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        internal val started = AtomicBoolean(false)
        internal val connection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
    }
    @Volatile private var ticket: DownloadTicket? = null
    private val running = AtomicBoolean(false)

    init {
        require(modelKey.matches(Regex("[a-zA-Z0-9_-]+")))
        require(specs.isNotEmpty() && specs.map { it.path }.distinct().size == specs.size)
        specs.forEach {
            require(it.path.matches(Regex("[a-zA-Z0-9_.-]+")) && it.path != "." && it.path != "..")
            require(it.size > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
            require(URL(it.url).protocol == "https")
        }
    }
    fun isDownloading() = running.get()
    fun cancelDownload() { ticket?.let { it.cancelled.set(true);it.connection.get()?.disconnect() } }
    fun storedBytes() = listOf(directory,staging).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    fun stagedBytes() = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun isReady(): Boolean = File(directory, "VERIFIED").isFile && specs.all { File(directory,it.path).length() == it.size }

    /** Must run off the UI thread. Hashes are checked again before every model load. */
    fun acquire(waitMillis: Long = 0): File {
        val deadline = System.nanoTime() + waitMillis.coerceIn(0, 10000) * 1_000_000
        while (using.get() && System.nanoTime() < deadline) Thread.sleep(25)
        synchronized(this) {
            check(!running.get()) { "模型下载中" }
            check(using.compareAndSet(false, true)) { "模型正在使用" }
        }
        try {
            check(isReady() && specs.all { matches(File(directory,it.path),it) }) { "模型缺失或校验失败，请重新下载" }
            return directory
        } catch (e: Exception) { using.set(false); throw e }
    }
    fun release() { using.set(false) }
    @Synchronized fun delete() {
        check(!using.get() && !running.get()) { "请先切换到系统语音引擎或取消下载，再删除模型" }
        check(!directory.exists() || directory.deleteRecursively()) { "模型删除失败" }
        check(!staging.exists() || staging.deleteRecursively()) { "临时文件清理失败" }
    }

    /** Reserve before enqueueing work so cancellation cannot be erased by a delayed worker. */
    @Synchronized fun prepareDownload(): DownloadTicket {
        check(!using.get()) { "请先停止使用模型" }
        check(running.compareAndSet(false,true)) { "下载已在进行" }
        return DownloadTicket().also { ticket=it }
    }
    fun download(progress: (Progress) -> Unit) = download(prepareDownload(),progress)
    fun download(token: DownloadTicket, progress: (Progress) -> Unit) {
        check(ticket === token && token.started.compareAndSet(false,true)) { "下载任务已失效" }
        try {
            cancelled(token)
            root.mkdirs();staging.mkdirs()
            staging.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { check(it.delete()) }
            val reusable=specs.filter { cancelled(token);matches(File(staging,it.path),it) }.sumOf { it.size }
            val remaining=totalBytes-reusable
            check(freeSpace(root) >= remaining + 64L * 1024 * 1024) { "存储空间不足，剩余下载需${remaining/1_000_000} MB，另留64 MiB空间" }
            var complete = 0L
            for (spec in specs) {
                cancelled(token)
                val target = File(staging, spec.path)
                if (matches(target,spec)) { complete += spec.size; progress(Progress(complete,totalBytes,spec.path)); continue }
                val partial = File(staging,spec.path + ".part")
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                try {
                    (open?.invoke(spec.url) ?: openModelHttps(spec.url,token)).use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            cancelled(token)
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= spec.size) { "下载文件大小异常" }
                            hash.update(buffer,0,n); output.write(buffer,0,n)
                            progress(Progress(complete + count,totalBytes,spec.path))
                        }
                        output.fd.sync()
                    } }
                    cancelled(token)
                    check(count == spec.size && hex(hash.digest()) == spec.sha256) { "文件完整性校验失败：${spec.path}" }
                    if (target.exists()) check(target.delete())
                    check(partial.renameTo(target)) { "无法保存模型文件" }
                    complete += count
                } finally { partial.delete() }
            }
            cancelled(token)
            // Only an entirely verified directory is ever marked loadable.
            File(staging,"VERIFIED").writeText(specs.joinToString("\n") { "${it.path} ${it.sha256}" })
            if (directory.exists()) check(directory.deleteRecursively()) { "无法替换旧模型" }
            check(staging.renameTo(directory)) { "无法完成模型安装" }
            progress(Progress(totalBytes,totalBytes,"完成"))
        } finally { synchronized(this) { if(ticket === token) { ticket=null;running.set(false) } } }
    }
    private fun cancelled(token: DownloadTicket) { if (token.cancelled.get()) throw IOException("下载已取消；已校验文件保留供重试，删除模型可清理全部临时数据") }
    private fun matches(file: File, spec: ModelFile): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(64*1024); while(true) { val n=input.read(buffer); if(n<0)break;hash.update(buffer,0,n) } }
        return hex(hash.digest()) == spec.sha256
    }
    companion object {
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        internal fun openModelHttps(value: String, token: DownloadTicket,
            connect: (URL)->HttpURLConnection = { it.openConnection() as HttpURLConnection }): InputStream {
            fun checkCancelled() { if(token.cancelled.get())throw IOException("下载已取消") }
            var url=URL(value)
            repeat(6) {
                checkCancelled()
                require(url.protocol == "https") { "禁止不安全的下载地址" }
                val connection=connect(url)
                token.connection.set(connection)
                var handedOff=false
                try {
                    checkCancelled()
                    connection.instanceFollowRedirects=false
                    connection.connectTimeout=15000;connection.readTimeout=10000
                    connection.setRequestProperty("User-Agent","InkDock-ModelDownloader/1")
                    val status=connection.responseCode
                    checkCancelled()
                    if (status in 300..399) {
                        val next=connection.getHeaderField("Location") ?: throw IOException("下载跳转缺少地址")
                        url=URL(url,next)
                    } else {
                        if(status != 200)throw IOException("下载失败 HTTP $status")
                        val stream=connection.inputStream
                        checkCancelled()
                        handedOff=true
                        return object: java.io.FilterInputStream(stream) {
                            override fun close() { try { super.close() } finally {
                                token.connection.compareAndSet(connection,null);connection.disconnect()
                            } }
                        }
                    }
                } finally {
                    if(!handedOff){token.connection.compareAndSet(connection,null);connection.disconnect()}
                }
            }
            throw IOException("下载跳转次数过多")
        }
    }
}
