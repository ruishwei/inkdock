package org.inkdock.reader

import android.content.Intent
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android-only helpers; none are packaged into the reader APK. */
internal object BackgroundReaderTestSupport {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun withReader(launchIntent: Intent? = null, test: (ActivityScenario<MainActivity>, MainActivity, WebView) -> Unit) {
        val launched = if (launchIntent == null) ActivityScenario.launch(MainActivity::class.java)
            else ActivityScenario.launch<MainActivity>(launchIntent)
        launched.use { scenario ->
            val reference = AtomicReference<MainActivity>()
            scenario.onActivity { reference.set(it) }
            val activity = reference.get()
            val web = webFor(activity)
            await("Reader document ready") {
                js(web, "typeof window.loadDocument==='function' && document.querySelectorAll('#content section').length>0") == "true"
            }
            val fixture = instrumentation.context.assets.open("background-reader.md").bufferedReader().use { it.readText() }
            if (launchIntent == null) js(web, "window.loadDocument(${JSONObject.quote(fixture)},0,20,false);true")
            await("Synthetic fixture rendered") {
                js(web, "document.getElementById('content').textContent.includes('Synthetic background reader fixture')") == "true"
            }
            try { test(scenario, activity, web) }
            finally {
                if (!instrumentation.targetContext.getSystemService(PowerManager::class.java)!!.isInteractive) wakeScreen()
                scenario.onActivity { current ->
                    // Use the current Activity because the test may have recreated it.
                    findWeb(current.window.decorView)?.resumeTimers()
                    current.Bridge().stopSpeaking()
                }
            }
        }
    }

    fun returnReaderToForeground(scenario: ActivityScenario<MainActivity>, original: MainActivity) {
        // Home changes Android task focus. moveToState(RESUMED) cannot bring that task back.
        // Preserve MAIN/LAUNCHER: MainActivity stores onNewIntent(), and ActivityScenario
        // ignores subsequent lifecycle events if the Activity's intent filter no longer matches.
        // This helper returns only launcher-started test Activities, never a share/file import.
        assertTrue("Foreground helper supports launcher intents only", onMain {
            original.intent.action == Intent.ACTION_MAIN && original.intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        })
        val component = original.componentName.flattenToString()
        val result = shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-reorder-to-front --activity-single-top -n $component")
        assertTrue("Android could not bring reader task forward: $result", !result.contains("Error:"))
        await("Original reader resumes after matching launcher task foregrounding") {
            readerIsResumed(original)
        }
        scenario.onActivity { current ->
            assertTrue("Task foregrounding must retain the original Activity", current === original)
        }
    }

    // ActivityScenario.state throws during legitimate STARTED/RESTARTED transitions.
    // The platform monitor exposes these stages directly so polling does not fail mid-transition.
    fun readerIsResumed(activity: MainActivity): Boolean = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED &&
            !activity.isDestroyed && activity.hasWindowFocus()
    }

    fun readerIsStopped(activity: MainActivity): Boolean = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED &&
            !activity.isDestroyed && !activity.hasWindowFocus()
    }

    fun systemHome() = shell("input keyevent 3")
    fun sleepScreen() = shell("input keyevent 223")
    fun wakeScreen() {
        shell("input keyevent 224")
        // Emulator has no credential lock; this cannot bypass a PIN/password lock.
        shell("wm dismiss-keyguard")
    }
    fun systemBack() = shell("input keyevent 4")

    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.bufferedReader().readText()
        }

    fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch (failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw it }
        return result.get()
    }

    fun js(web: WebView, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        onMain { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
        assertTrue("JavaScript evaluation timed out", latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    fun await(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out: $description")
    }

    fun assertRemains(description: String, durationMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        do {
            assertTrue(description, condition())
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    fun webFor(activity: MainActivity): WebView = onMain { findWeb(activity.window.decorView) ?: error("Reader WebView missing") }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
}
