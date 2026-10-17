package org.inkdock.reader

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.os.PowerManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.assertRemains
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.instrumentation
import org.inkdock.reader.BackgroundReaderTestSupport.js
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.readerIsStopped
import org.inkdock.reader.BackgroundReaderTestSupport.returnReaderToForeground
import org.inkdock.reader.BackgroundReaderTestSupport.systemHome
import org.inkdock.reader.BackgroundReaderTestSupport.sleepScreen
import org.inkdock.reader.BackgroundReaderTestSupport.wakeScreen
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.inkdock.reader.BackgroundReaderTestSupport.webFor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real platform Home, TTS Binder, foreground-service notification, and PendingIntent actions.
 * Uses only the test APK's silent synthetic engine. Passing is NOT audible/voice-quality proof.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechBackgroundDeviceTest {
    @Test fun homeAndScreenOffContinueAcrossThreeNativeSegmentsAndNotificationStopIsTerminal() =
        withSilentReader { scenario, activity, web, service ->
            js(web, "document.getElementById('speak').click();true")
            await("First silent fixture segment starts") { state(service).let { it.optString("phase") == "started" && it.optInt("queueIndex") == 0 } }
            val queueId = state(service).getString("queueId")
            assertEquals("Whole document queue accepted before Home", 12, state(service).getInt("queueSize"))

            assertFalse("Screen-off regression requires the CI emulator without a credential lock",
                activity.getSystemService(KeyguardManager::class.java)!!.isKeyguardSecure)
            val power = activity.getSystemService(PowerManager::class.java)!!
            // Disable renderer status handlers as well as JS timers. Advancement must be native.
            js(web, "window.__originalOfflineStatus=window.onOfflineQueueStatus;window.__originalSpeechStatus=window.onSpeechStatus;window.onOfflineQueueStatus=()=>{};window.onSpeechStatus=()=>{};true")
            onMain { web.pauseTimers() }
            systemHome()
            await("Actual Home backgrounds reader") {
                readerIsStopped(activity)
            }
            sleepScreen()
            await("Platform screen is actually off") { !power.isInteractive }
            val firstBackgroundIndex = state(service).getInt("queueIndex")
            await("Three more segments start while screen and WebView are suspended", 30_000) {
                assertFalse("Screen must remain off during native queue advancement", power.isInteractive)
                val current = state(service)
                assertEquals("Home must not replace the native queue", queueId, current.optString("queueId"))
                assertTrue("Native queue ended before crossing background segments: $current", current.optBoolean("active"))
                current.optString("phase") == "started" && current.optInt("queueIndex") >= firstBackgroundIndex + 3
            }
            assertTrue("Reader must remain in background", readerIsStopped(activity))
            val foreground = notification()
            assertTrue("Playing notification must represent a foreground Service", foreground.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals("transport", foreground.category)
            assertTrue("Notification must carry the active MediaSession", foreground.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            assertFalse("Notification must not expose fixture document text", foreground.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(SilentFixtureTtsService.TEXT_PREFIX))

            // Use the action attached to the actual published Android notification.
            notificationAction("停止").actionIntent.send()
            await("Notification Stop ends native queue") { state(service).let { !it.optBoolean("active") && it.optString("phase") == "stopped" } }
            val stoppedIndex = state(service).getInt("queueIndex")
            await("Notification removed after Stop") { !hasNotification() }
            assertRemains("Late engine callbacks must not restart or advance the stopped queue", SilentFixtureTtsService.SEGMENT_MILLIS + 700) {
                state(service).let { !it.optBoolean("active") && it.optString("phase") == "stopped" && it.optInt("queueIndex") == stoppedIndex } && !hasNotification()
            }

            wakeScreen()
            await("Screen wakes after lock-screen Stop") { power.isInteractive }
            onMain { web.resumeTimers() }
            returnReaderToForeground(scenario, activity)
            await("Reader is foreground again") { onMain { activity.hasWindowFocus() } }
            js(web, "window.onOfflineQueueStatus=window.__originalOfflineStatus;window.onSpeechStatus=window.__originalSpeechStatus;Native.getSpeechState();true")
            await("Stopped snapshot restores reader controls") {
                js(web, "document.getElementById('stopIcon').hasAttribute('hidden') && document.querySelectorAll('.speaking').length===0") == "true"
            }
        }

    @Test fun notificationPauseHoldsCurrentSegmentAndResumeRestartsInBackground() =
        withSilentReader { scenario, activity, web, service ->
            js(web, "document.getElementById('speak').click();true")
            await("Silent fixture playback begins") { state(service).optString("phase") == "started" }
            systemHome()
            await("Home backgrounds reader before notification control") {
                readerIsStopped(activity)
            }
            notificationAction("暂停").actionIntent.send()
            await("Notification Pause reaches native state") { state(service).optString("phase") == "paused" }
            val paused = state(service)
            val index = paused.getInt("queueIndex")
            val queueId = paused.getString("queueId")
            assertRemains("Paused queue must retain the same segment despite engine stop callback", SilentFixtureTtsService.SEGMENT_MILLIS + 700) {
                state(service).let { it.optBoolean("active") && it.optString("phase") == "paused" && it.optInt("queueIndex") == index && it.optString("queueId") == queueId }
            }
            notificationAction("继续").actionIntent.send()
            await("Resume restarts the same segment") {
                state(service).let { it.optString("phase") == "started" && it.optInt("queueIndex") == index && it.optString("queueId") == queueId }
            }
            await("Resumed queue advances in background", 15_000) {
                state(service).let { it.optBoolean("active") && it.optInt("queueIndex") > index }
            }
            assertTrue("Notification controls must not bring reader forward", readerIsStopped(activity))
            notificationAction("停止").actionIntent.send()
            await("Resumed playback stops") { !state(service).optBoolean("active") && !hasNotification() }
        }

    @Test fun recreatingSharedDocumentActivityKeepsTheSameNativeQueueAndRestoresHighlight() =
        withSilentReader(importFixture = true) { scenario, originalActivity, web, service ->
            js(web, "document.getElementById('speak').click();true")
            await("Shared synthetic document advances before recreation", 15_000) {
                state(service).let { it.optString("phase") == "started" && it.optInt("queueIndex") >= 1 }
            }
            val before = state(service)
            val queueId = before.getString("queueId")
            val index = before.getInt("queueIndex")
            scenario.recreate()
            val replacement = AtomicReference<MainActivity>()
            scenario.onActivity { replacement.set(it) }
            assertNotSame("Recreation must create a genuinely new Activity", originalActivity, replacement.get())
            val newWeb = webFor(replacement.get())
            assertSame("Foreground speech Service must survive Activity destruction", service, serviceReference(instrumentation.targetContext))
            await("Recreated shared document restores active reading state") {
                val native = state(service)
                assertEquals("ACTION_SEND must not reimport and replace the queue on recreation", queueId, native.optString("queueId"))
                assertTrue("Recreation must not stop native speech", native.optBoolean("active"))
                assertTrue("Recreation must not rewind the queue", native.optInt("queueIndex") >= index)
                js(newWeb, "document.getElementById('content').textContent.includes('Synthetic background reader fixture') && !document.getElementById('stopIcon').hasAttribute('hidden') && document.querySelectorAll('.speaking').length===1") == "true"
            }
            await("Native queue crosses two further segments after recreation", 20_000) {
                state(service).let { it.optBoolean("active") && it.optString("queueId") == queueId && it.optString("phase") == "started" && it.optInt("queueIndex") >= index + 2 }
            }
            notificationAction("停止").actionIntent.send()
            await("Recreated reader handles notification Stop") { !state(service).optBoolean("active") && !hasNotification() }
        }

    private fun withSilentReader(importFixture: Boolean = false, test: (ActivityScenario<MainActivity>, MainActivity, WebView, OfflineSpeechService) -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val voiceBefore = prefs.all.filterKeys { it.startsWith("voice.") }
        val activeFile = File(context.filesDir, "active.md")
        val activeBefore = if (importFixture && activeFile.exists()) activeFile.readBytes() else null
        val shareIntent = if (importFixture) Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, instrumentation.context.assets.open("background-reader.md").bufferedReader().use { it.readText() })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
        try {
            withReader(shareIntent) { scenario, activity, web ->
                val service = serviceReference(context)
                try {
                    val packageName = instrumentation.context.packageName
                    // The test engine is selected through the normal user-facing engine bridge.
                    js(web, "Native.selectVoiceEngine(${JSONObject.quote(packageName)});true")
                    await("Test-APK offline engine and voice initialized") {
                        val voice = onMain { service.voiceSettings() }
                        voice.optString("enginePackage") == packageName && !voice.optBoolean("loading") &&
                            voice.optBoolean("initialized") && voice.optString("current") == SilentFixtureTtsService.VOICE_NAME
                    }
                    test(scenario, activity, web, service)
                } finally { onMain { service.stopPlayback() } }
            }
        } finally {
            restoreVoicePreferences(prefs, voiceBefore)
            if (importFixture) {
                if (activeBefore != null) activeFile.writeBytes(activeBefore)
                else if (activeFile.exists()) assertTrue("Remove only the synthetic imported fixture", activeFile.delete())
            }
        }
    }

    /** Unbind immediately; the test itself must not extend the Service's Android lifetime. */
    private fun serviceReference(context: Context): OfflineSpeechService {
        val reference = AtomicReference<OfflineSpeechService>()
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                reference.set((binder as OfflineSpeechService.LocalBinder).service)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, OfflineSpeechService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Native speech Service binding timed out", ready.await(10, TimeUnit.SECONDS))
            return reference.get()
        } finally { context.unbindService(connection) }
    }

    private fun state(service: OfflineSpeechService): JSONObject = onMain { service.stateSnapshot() }
    private fun notifications() = instrumentation.targetContext.getSystemService(NotificationManager::class.java)!!.activeNotifications
    private fun hasNotification() = notifications().any { it.id == OfflineSpeechService.NOTIFICATION }
    private fun notification(): Notification = notifications().firstOrNull { it.id == OfflineSpeechService.NOTIFICATION }?.notification
        ?: throw AssertionError("Native offline playback notification is missing")
    private fun notificationAction(title: String): Notification.Action = notification().actions.orEmpty().firstOrNull { it.title.toString() == title }
        ?: throw AssertionError("Expected published notification action: $title")

    @Suppress("UNCHECKED_CAST")
    private fun restoreVoicePreferences(prefs: SharedPreferences, before: Map<String, *>) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("voice.") }.forEach(edit::remove)
        before.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value as Set<String>)
            }
        }
        assertTrue("Original voice preferences restored", edit.commit())
    }
}
