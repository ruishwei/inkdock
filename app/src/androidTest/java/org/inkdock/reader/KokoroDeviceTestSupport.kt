package org.inkdock.reader

import android.content.Context
import android.os.SystemClock
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.instrumentation
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File

/** Instrumentation-only fixtures: no public controls, download, user text, or model substitution. */
internal object KokoroDeviceTestSupport {
    val context get() = instrumentation.targetContext
    val artifacts: File get() = File(context.filesDir, "native-test-artifacts").apply { mkdirs() }
    const val TEXT = "你好，世界。"
    const val NEXT_TEXT = "今天天气很好。"

    fun service(activity: MainActivity): OfflineSpeechService = onMain {
        MainActivity::class.java.getDeclaredField("offline").apply { isAccessible = true }.get(activity) as OfflineSpeechService
    }
    fun awaitService(activity: MainActivity) = await("Reader owns its native speech Service") {
        onMain { MainActivity::class.java.getDeclaredField("offline").apply { isAccessible = true }.get(activity) != null }
    }
    fun variants(): List<KokoroManifest.Variant> {
        val raw = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("kokoroVariants", "float,int8")
        val ids = raw.split(',').map { it.trim() }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in setOf("float", "int8") })
        return ids.map(KokoroManifest::variant)
    }
    fun writeReport(file: String, key: String, value: JSONObject) {
        require(file in setOf("real-model-metrics.json", "synthetic-prefetch-metrics.json"))
        val target = File(artifacts, file)
        val report = if (target.isFile) JSONObject(target.readText()) else JSONObject().put("schemaVersion", 1)
        report.put(key, value)
        val temporary = File(artifacts, "$file.part")
        temporary.writeText(report.toString(2))
        assertTrue("Publish synthetic test report", temporary.renameTo(target))
    }
    fun request(id: String, text: List<String>) = OfflineSpeechRequest(id,
        text.mapIndexed { index, value -> OfflineSpeechSegment(value, -1, "$id-$index") })

    @Suppress("UNCHECKED_CAST")
    fun withPreferencesRestored(test: () -> Unit) {
        val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val original = prefs.all.filterKeys { it.startsWith("voice.") }
        val modelPrefs = context.getSharedPreferences("kokoro", Context.MODE_PRIVATE)
        val originalVariant = modelPrefs.getString("variant", null)
        // Ensure launching the test Activity does not preload a second model before selection.
        assertTrue(prefs.edit().putString("voice.engine", "org.inkdock.synthetic.unavailable").commit())
        try { test() }
        finally {
            instrumentation.waitForIdleSync()
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("voice.") }.forEach(edit::remove)
            original.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value as Set<String>)
            } }
            assertTrue(edit.commit())
            assertTrue(modelPrefs.edit().apply {
                if (originalVariant == null) remove("variant") else putString("variant", originalVariant)
            }.commit())
        }
    }
    fun elapsed(start: Long) = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
}
