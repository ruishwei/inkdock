package org.inkdock.reader

import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.PowerManager
import android.os.SystemClock
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real elapsed-time integration, never a shortened deadline or fake device clock.
 * Uses the APK-only silent TTS fixture and synthetic text. No provider request is authorized.
 * Polling stateSnapshot is passive: unlike sleepTimerSnapshot it cannot cause expiry itself.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerDeviceTest {
    private var evidence: JSONObject? = null
    private var retainedEngine: OfflineSpeechEngine? = null
    @get:Rule val evidenceWriter = object : TestWatcher() {
        override fun succeeded(description: Description) = writeOutcome(description, true)
        override fun failed(error: Throwable, description: Description) = writeOutcome(description, false)
    }
    private fun writeOutcome(description: Description, passed: Boolean) {
        val row = evidence ?: JSONObject().put("timingBasis", "SystemClock.elapsedRealtime_real_device")
            .put("activeQueue", false).put("resourcesReleased", passed)
        row.put("test", description.methodName).put("passed", passed)
        val directory = File(instrumentation.targetContext.filesDir, "native-test-artifacts").apply { mkdirs() }
        File(directory, "sleep-timer-device-metrics.jsonl").appendText(row.toString() + "\n")
    }

    @Test fun foregroundPausedTimerExpiresAfterRealMinute() = withSilentReader { _, _, web, service ->
        startQueue(service, "foreground")
        val armed = arm(web, service)
        val token = onMain { field(service, "utteranceId") as String? }
        onMain { service.pausePlayback() }
        val remaining = timer(service).getLong("remainingMs")
        SystemClock.sleep(1_200)
        assertTrue("Pause must count down", timer(service).getLong("remainingMs") < remaining - 900)
        onMain { service.resumePlayback() }
        await("Resume returns to started") { state(service).optString("phase") == "started" }
        onMain { service.pausePlayback() }
        assertEquals("paused", state(service).getString("phase"))
        waitForExpiry(service, armed, "foreground")
        assertReleased(service)
        // Deliberately deliver an old engine callback after expiry, not a new user gesture.
        onMain {
            OfflineSpeechService::class.java.getDeclaredMethod("speechEvent", String::class.java, String::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(service, token, "done", null)
            service.resumePlayback()
            service.submitQueue(queue("late-queue"), "synthetic-timer")
        }
        assertRemains("Late done/resume/submission cannot restart after expiry", 2_500) {
            !state(service).optBoolean("active") && timer(service).optBoolean("expired")
        }
        assertReleased(service)
        record("foregroundPausedTimerExpiresAfterRealMinute", "foreground", armed, service, false, false)
    }

    @Test fun homeTimerExpiresAfterRealMinute() = realBackgroundExpiry(false)
    @Test fun screenOffTimerExpiresAfterRealMinute() = realBackgroundExpiry(true)

    private fun realBackgroundExpiry(screenOff: Boolean) = withSilentReader { scenario, activity, web, service ->
        startQueue(service, if (screenOff) "screen-off" else "home")
        val armed = arm(web, service)
        // Expiry must work without WebView callbacks or renderer timers.
        js(web, "window.onSpeechStatus=()=>{};window.onOfflineQueueStatus=()=>{};window.onSleepTimerStatus=()=>{};true")
        onMain { web.pauseTimers() }
        systemHome()
        await("Actual Home stops Activity") { readerIsStopped(activity) }
        val power = activity.getSystemService(PowerManager::class.java)!!
        if (screenOff) {
            assertFalse("Test must not bypass a credential lock", activity.getSystemService(KeyguardManager::class.java)!!.isKeyguardSecure)
            sleepScreen()
            await("Display really turns off") { !power.isInteractive }
        }
        val surface = if (screenOff) "screen_off" else "background"
        waitForExpiry(service, armed, surface) {
            assertTrue("Reader must remain stopped at expiry", readerIsStopped(activity))
            if (screenOff) assertFalse("Display must remain off at expiry", power.isInteractive)
        }
        assertReleased(service)
        record(if (screenOff) "screenOffTimerExpiresAfterRealMinute" else "homeTimerExpiresAfterRealMinute",
            surface, armed, service, readerIsStopped(activity), !power.isInteractive)
        if (screenOff) wakeScreen()
        onMain { web.resumeTimers() }
        returnReaderToForeground(scenario, activity)
        onMain { service.resumePlayback() }
        assertRemains("Foregrounding/resuming cannot restart expired queue", 1_000) { !state(service).optBoolean("active") }
    }

    @Test fun cancelReplaceAndManualStopRespectDeadline() = withSilentReader { _, _, web, service ->
        startQueue(service, "original")
        for (minutes in listOf(15, 30, 60, 720, 1)) {
            js(web, "Native.setSleepTimer($minutes);true")
            await("Bridge accepts supported $minutes minute duration") { timer(service).optInt("durationMinutes") == minutes }
        }
        val before = timer(service).getLong("remainingMs")
        onMain {
            assertFalse(service.setSleepTimer(0)); assertFalse(service.setSleepTimer(721))
            assertFalse(service.setSleepTimer(-1))
            service.submitQueue(queue("replacement"), "synthetic-timer")
        }
        await("Replacement begins") { state(service).optString("queueId") == "replacement" && state(service).optString("phase") == "started" }
        assertTrue(timer(service).getBoolean("active"))
        assertTrue("Replacement must preserve, not restart deadline", timer(service).getLong("remainingMs") <= before)
        js(web, "Native.cancelSleepTimer();true")
        await("Cancel clears only timer") { !timer(service).optBoolean("active") }
        assertTrue("Cancelling timer keeps playback", state(service).getBoolean("active"))
        js(web, "Native.setSleepTimer(1);true")
        await("Timer rearmed") { timer(service).optBoolean("active") }
        js(web, "Native.stopSpeaking();true")
        await("Manual Stop cancels queue and timer") { !state(service).optBoolean("active") && !timer(service).optBoolean("active") }
        assertFalse(timer(service).getBoolean("expired"))
        assertReleased(service)
    }

    @Test fun recreationAndServiceRestartDoNotAutoResume() {
        lateinit var old: OfflineSpeechService
        withSilentReader { scenario, _, _, service ->
            old = service
            startQueue(service, "recreation")
            onMain { assertTrue(service.setSleepTimer(1)); service.pausePlayback() }
            val before = timer(service).getLong("remainingMs")
            scenario.recreate()
            val activity = AtomicReference<MainActivity>()
            scenario.onActivity { activity.set(it) }
            await("Recreated Activity binds surviving Service") { onMain { field(activity.get(), "offline") === service } }
            assertEquals("paused", state(service).getString("phase"))
            assertTrue(timer(service).getBoolean("active"))
            assertTrue(timer(service).getLong("remainingMs") <= before)
            onMain { service.stopPlayback() }
        }
        await("Android destroys unbound stopped Service") { onMain { field(old, "destroyed") == true } }
        withReader { _, activity, _ ->
            val service = serviceFor(activity)
            assertNotSame(old, service)
            assertFalse("Fresh Service must not restore queue", state(service).getBoolean("active"))
            assertFalse("Fresh Service must not restore timer", timer(service).getBoolean("active"))
            assertEquals(android.app.Service.START_NOT_STICKY, onMain { service.onStartCommand(null, 0, 1) })
            assertRemains("Restart must not auto-resume", 1_500) { !state(service).optBoolean("active") }
            assertReleased(service)
        }
    }

    @Test fun pendingCloudConsentExpiresWithoutSynthesis() = withReader { _, activity, web ->
        val service = serviceFor(activity)
        val provider = CloudVoiceProvider.QWEN
        val keys = CloudVoiceKeyStore(activity)
        assumeTrue("Never overwrite a real provider key", keys.status(provider) == CloudVoiceKeyStatus.MISSING)
        val cloud = onMain { (field(activity, "cloud\$delegate") as Lazy<*>).value as CloudSpeechUi }
        try {
            keys.save(provider, "synthetic-instrumentation-not-a-provider-key".toCharArray())
            js(web, "window.__timerCloudEvents=[];const originalTimerStatus=window.onSpeechStatus;window.onSpeechStatus=e=>{window.__timerCloudEvents.push(e);originalTimerStatus(e)};Native.setSpeechSource('QWEN');true")
            // source change itself cancels prior playback, so arm only after that bridge operation.
            await("Cloud source selected") { onMain { field(activity, "selectedCloud") == provider } }
            val armed = arm(web, service)
            js(web, "Native.previewCloudVoice();true")
            await("Synthetic-key consent is pending") { onMain { (field(cloud, "dialog") as AlertDialog?)?.isShowing == true } }
            val consent = onMain { field(cloud, "dialog") as AlertDialog }
            assertNoCloudSynthesis(cloud, web)
            waitForExpiry(service, armed, "cloud_pending_consent")
            await("Timer dismisses outstanding consent") { onMain { !consent.isShowing } }
            assertNoCloudSynthesis(cloud, web)
            assertReleased(service)
            record("pendingCloudConsentExpiresWithoutSynthesis", "cloud_pending_consent", armed, service, false, false)
            // No positive click, HTTP request, paid provider call, or real key is used.
        } finally {
            onMain { cloud.stop() }
            keys.remove(provider)
        }
    }

    private fun queue(id: String) = OfflineSpeechRequest(id, (0 until 120).map {
        OfflineSpeechSegment("${SilentFixtureTtsService.TEXT_PREFIX} sleep timer segment $it", it, "$id-$it")
    })
    private fun startQueue(service: OfflineSpeechService, id: String) {
        onMain { service.beginUserPlayback(); service.submitQueue(queue(id), "synthetic-timer") }
        await("Silent fixture queue starts") { state(service).optString("phase") == "started" }
    }
    private fun arm(web: WebView, service: OfflineSpeechService): Long {
        // Timestamp precedes the bridge dispatch, therefore 60 seconds is a strict lower bound.
        retainedEngine = onMain { field(service, "tts") as OfflineSpeechEngine? }
        val armed = SystemClock.elapsedRealtime()
        js(web, "Native.setSleepTimer(1);true")
        await("Native minute timer armed") { timer(service).optBoolean("active") && timer(service).optInt("durationMinutes") == 1 }
        return armed
    }
    private fun waitForExpiry(service: OfflineSpeechService, armed: Long, surface: String, verifySurface: () -> Unit = {}) {
        val deadline = armed + 85_000
        while (SystemClock.elapsedRealtime() < deadline) {
            verifySurface()
            val current = state(service)
            val timer = current.getJSONObject("sleepTimer")
            if (timer.optBoolean("expired")) {
                assertTrue("$surface cannot expire before a real minute", SystemClock.elapsedRealtime() - armed >= 60_000)
                assertFalse("Expired queue must be inactive", current.optBoolean("active"))
                assertFalse(timer.getBoolean("active"))
                return
            }
            assertTrue("$surface timer unexpectedly cancelled", timer.optBoolean("active"))
            SystemClock.sleep(150)
        }
        fail("Real 1-minute timer failed to expire on $surface: ${state(service)}")
    }
    private fun state(service: OfflineSpeechService) = onMain { service.stateSnapshot() }
    private fun timer(service: OfflineSpeechService) = state(service).getJSONObject("sleepTimer")
    private fun assertReleased(service: OfflineSpeechService) {
        await("Foreground notification removed") {
            instrumentation.targetContext.getSystemService(NotificationManager::class.java)!!.activeNotifications.none { it.id == OfflineSpeechService.NOTIFICATION }
        }
        onMain {
            assertNull("Queued text released", field(service, "request"))
            assertNull("Utterance token invalidated", field(service, "utteranceId"))
            assertEquals(false, field(service, "focused"))
            assertEquals(false, field(service, "foregroundService"))
            assertFalse((field(service, "session") as MediaSession).isActive)
            val wakeLock = (field(service, "wakeLock\$delegate") as Lazy<*>).value as PowerManager.WakeLock
            assertFalse("Playback wake lock released", wakeLock.isHeld)
        }
        val state = state(service)
        assertEquals(0, state.getInt("bufferedSlots"))
        assertEquals(0, state.getInt("retainedAudioBuffers"))
        assertFalse(state.getBoolean("nextAudioReady"))
        // Keep the old engine reference: a null service field cannot prove resource cleanup.
        retainedEngine?.let { engine ->
            onMain {
                assertEquals(0, engine.bufferedSlots)
                assertEquals(0, engine.retainedAudioBuffers)
                assertFalse(engine.nextAudioReady)
                if (engine is FileBufferedOfflineSpeechEngine) {
                    assertTrue("Original engine shutdown, not merely dereferenced", field(engine, "closed") == true)
                    assertNull("Original queued text released", field(engine, "original"))
                    assertNull("Prefetched queued text released", field(engine, "following"))
                    assertTrue("Original synthesis/player work map empty", (field(engine, "work") as Map<*, *>).isEmpty())
                    val directory = field(engine, "directory") as File
                    val prefix = field(engine, "prefix") as String
                    assertTrue("Owned synthetic audio files removed", directory.listFiles().orEmpty().none { it.name.startsWith(prefix) })
                }
            }
        }
    }
    private fun record(test: String, surface: String, armed: Long, service: OfflineSpeechService, home: Boolean, screenOff: Boolean) {
        val observed = SystemClock.elapsedRealtime()
        val current = state(service)
        val row = JSONObject().put("test", test).put("surface", surface).put("durationMinutes", 1)
            .put("requestedMs", 60_000).put("armedElapsedRealtimeMs", armed).put("observedExpiryElapsedRealtimeMs", observed)
            .put("elapsedMs", observed - armed).put("timingBasis", "SystemClock.elapsedRealtime_real_device")
            .put("expired", timer(service).getBoolean("expired")).put("activeQueue", current.getBoolean("active"))
            .put("resourcesReleased", true).put("activityStoppedAtExpiry", home).put("screenOffAtExpiry", screenOff)
            .put("bufferedSlots", current.getInt("bufferedSlots")).put("retainedAudioBuffers", current.getInt("retainedAudioBuffers"))
        evidence = row
    }
    private fun assertNoCloudSynthesis(cloud: CloudSpeechUi, web: WebView) {
        assertEquals("true", js(web, "window.__timerCloudEvents.every(e=>e.phase!=='queued' && e.phase!=='started')"))
        onMain { for (name in listOf("cancellation", "player", "audioFile")) assertNull(name, field(cloud, name)) }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun serviceFor(activity: MainActivity): OfflineSpeechService {
        await("Activity binds speech Service") { onMain { field(activity, "offline") != null } }
        return onMain { field(activity, "offline") as OfflineSpeechService }
    }
    private fun withSilentReader(test: (ActivityScenario<MainActivity>, MainActivity, WebView, OfflineSpeechService) -> Unit) {
        val prefs = instrumentation.targetContext.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val before = prefs.all.filterKeys { it.startsWith("voice.") }
        try {
            withReader { scenario, activity, web ->
                val service = serviceFor(activity)
                js(web, "Native.selectVoiceEngine(${JSONObject.quote(instrumentation.context.packageName)});true")
                await("Test APK silent engine initialized") {
                    onMain { service.voiceSettings() }.let {
                        it.optString("enginePackage") == instrumentation.context.packageName &&
                            it.optBoolean("initialized") && !it.optBoolean("loading") &&
                            it.optString("current") == SilentFixtureTtsService.VOICE_NAME
                    }
                }
                try { test(scenario, activity, web, service) } finally { onMain { service.stopPlayback() } }
            }
        } finally {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("voice.") }.forEach(edit::remove)
            before.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                }
            }
            assertTrue(edit.commit())
        }
    }
}
