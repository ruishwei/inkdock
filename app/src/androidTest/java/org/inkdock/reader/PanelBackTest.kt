package org.inkdock.reader

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real Android Back dispatcher; it does not call JS closePanels itself. */
@RunWith(AndroidJUnit4::class)
class PanelBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun android15SystemBackDismissesEachPanelWithoutLeavingReader() {
        assumeTrue("Android 15 regression target", Build.VERSION.SDK_INT >= 35)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = AtomicReference<MainActivity>()
            val web = AtomicReference<WebView>()
            scenario.onActivity {
                activity.set(it)
                web.set(findWebView(it.window.decorView) ?: error("Reader WebView missing"))
            }
            awaitJs(web.get(), "typeof window.loadDocument === 'function' && document.querySelectorAll('#content section').length > 0")
            val fixture = instrumentation.context.assets.open("regression.md").bufferedReader().use { it.readText() }
            evaluate(web.get(), "window.loadDocument(${JSONObject.quote(fixture)},0,20,false); true")
            awaitJs(web.get(), "document.getElementById('content').textContent.includes('Synthetic regression document')")
            assertEquals("3", evaluate(web.get(), "document.querySelectorAll('#toc .toc-title').length"))

            val panels = listOf(
                "tocPanel" to "document.getElementById('tocButton').click()",
                "morePanel" to "document.getElementById('moreButton').click()",
                "voicePanel" to "document.getElementById('moreButton').click();document.getElementById('voiceSettings').click()"
            )
            // Repeat to catch stale callback registration after dismissal/reopening.
            repeat(2) {
                for ((panel, open) in panels) {
                    evaluate(web.get(), "$open; true")
                    awaitJs(web.get(), "!document.getElementById('$panel').hidden")
                    instrumentation.waitForIdleSync()
                    // JS bridge calls cross threads before registration reaches the main looper.
                    SystemClock.sleep(200)
                    systemBack()
                    awaitJs(web.get(), "['tocPanel','morePanel','voicePanel'].every(id=>document.getElementById(id).hidden)")
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { current ->
                        assertTrue("Back must retain original Activity", activity.get() === current)
                        assertFalse("Back must not finish reader", current.isFinishing)
                        assertFalse("Back must not destroy reader", current.isDestroyed)
                        assertTrue("Reader must remain foreground after panel Back", current.hasWindowFocus())
                    }
                    assertEquals("RESUMED", scenario.state.name)
                    assertEquals("true", evaluate(web.get(), "document.getElementById('content').textContent.includes('Synthetic regression document')"))
                }
            }
            // With every panel closed, the app must yield Back to normal platform navigation.
            SystemClock.sleep(200)
            systemBack()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (scenario.state.name == "RESUMED" && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertFalse("Back with no panel must leave the foreground reader", scenario.state.name == "RESUMED")
        }
    }

    private fun systemBack() {
        val fd = instrumentation.uiAutomation.executeShellCommand("input keyevent 4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun evaluate(web: WebView, script: String): String {
        val result = AtomicReference<String>()
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); complete.countDown() } }
        assertTrue("JavaScript evaluation timed out: $script", complete.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitJs(web: WebView, condition: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            if (evaluate(web, "Boolean($condition)") == "true") return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("WebView condition timed out: $condition")
    }
}
