package org.inkdock.reader

import android.app.AlertDialog
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No positive consent clicks, provider calls, real keys, or audible-output assertions. */
@RunWith(AndroidJUnit4::class)
class CloudSpeechUiLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val provider = CloudVoiceProvider.QWEN

    @Test fun configBackAndCancelKeepReaderAndDoNotSave() = withReader { scenario, activity, web, cloud ->
        val prefs = activity.getSharedPreferences("cloud_voice", 0)
        val before = prefs.all.toMap()
        repeat(2) { attempt ->
            js(web, "Native.configureCloudVoice('QWEN');true")
            await { onMain { dialog(cloud)?.isShowing == true } }
            val opened = onMain { dialog(cloud)!! }
            if (attempt == 0) systemBack()
            else onMain { opened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            await { onMain { !opened.isShowing } }
            assertEquals(before, prefs.all)
            assertReader(scenario, activity, web)
            assertNoSynthesis(cloud, web)
        }
    }

    @Test fun previewWithoutKeyShowsFailureBeforeConsentOrSynthesis() = withReader { scenario, activity, web, cloud ->
        val keys = CloudVoiceKeyStore(activity)
        assumeTrue("Never modify a pre-existing key", keys.status(provider) == CloudVoiceKeyStatus.MISSING)
        js(web, "Native.previewCloudVoice();true")
        await { js(web, "document.getElementById('playbackStatus').textContent.includes('API key')") == "true" }
        assertEquals("true", js(web, "window.__cloudTestEvents.some(e=>e.phase==='error')"))
        assertNull(onMain { dialog(cloud) })
        assertReader(scenario, activity, web)
        assertNoSynthesis(cloud, web)
    }

    @Test fun syntheticKeyConsentBackAndCancelNeverQueueSynthesis() = withReader { scenario, activity, web, cloud ->
        val keys = CloudVoiceKeyStore(activity)
        assumeTrue("Never replace an existing key", keys.status(provider) == CloudVoiceKeyStatus.MISSING)
        try {
            keys.save(provider, "synthetic-instrumentation-not-a-provider-key".toCharArray())
            repeat(2) { attempt ->
                js(web, "Native.previewCloudVoice();true")
                await { onMain { dialog(cloud)?.isShowing == true } }
                val consent = onMain { dialog(cloud)!! }
                assertTrue(onMain { consent.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("同意发送") })
                assertNoSynthesis(cloud, web)
                // Deliberately never touch the positive button.
                if (attempt == 0) systemBack()
                else onMain { consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                await { onMain { !consent.isShowing } }
                await { js(web, "document.getElementById('playbackStatus').textContent.includes('没有发送本段')") == "true" }
                assertReader(scenario, activity, web)
                assertNoSynthesis(cloud, web)
            }
        } finally {
            keys.remove(provider)
        }
        assertEquals(CloudVoiceKeyStatus.MISSING, keys.status(provider))
    }

    private fun withReader(test: (ActivityScenario<MainActivity>, MainActivity, WebView, CloudSpeechUi) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ref = AtomicReference<MainActivity>()
            scenario.onActivity { ref.set(it) }
            val activity = ref.get()
            val web = onMain { findWeb(activity.window.decorView) ?: error("Missing reader WebView") }
            await { js(web, "typeof window.loadDocument==='function' && document.querySelectorAll('#content section').length>0") == "true" }
            js(web, "window.loadDocument('# Cloud lifecycle fixture\\n\\nSynthetic public test text.',0,20,false);window.__cloudTestEvents=[];const original=window.onSpeechStatus;window.onSpeechStatus=e=>{window.__cloudTestEvents.push(e);original(e)};document.getElementById('voiceSettings').click();Native.setSpeechSource('QWEN');true")
            await { js(web, "document.getElementById('speechSource').value==='QWEN'") == "true" }
            // Inspect existing native state without adding test interfaces to the app.
            val cloud = onMain {
                val lazy = MainActivity::class.java.getDeclaredField("cloud\$delegate").apply { isAccessible = true }.get(activity) as Lazy<*>
                lazy.value as CloudSpeechUi
            }
            test(scenario, activity, web, cloud)
        }
    }

    private fun assertReader(scenario: ActivityScenario<MainActivity>, original: MainActivity, web: WebView) {
        await { onMain { original.hasWindowFocus() } }
        scenario.onActivity { assertSame(original, it); assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
        assertEquals("RESUMED", scenario.state.name)
        assertEquals("true", js(web, "document.getElementById('content').textContent.includes('Cloud lifecycle fixture')"))
    }

    private fun assertNoSynthesis(cloud: CloudSpeechUi, web: WebView) {
        assertEquals("true", js(web, "window.__cloudTestEvents.every(e=>e.phase!=='queued' && e.phase!=='started')"))
        onMain {
            // synthesize() assigns cancellation before scheduling its worker.
            for (name in listOf("cancellation", "player", "audioFile")) {
                assertNull("Unexpected native synthesis state: $name", CloudSpeechUi::class.java.getDeclaredField(name).apply { isAccessible = true }.get(cloud))
            }
        }
    }

    private fun dialog(cloud: CloudSpeechUi): AlertDialog? =
        CloudSpeechUi::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(cloud) as AlertDialog?

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun systemBack() {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
    }
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun js(web: WebView, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
        assertTrue("JS evaluation timeout", latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < end)
        fail("Cloud UI lifecycle condition timed out")
    }
}
