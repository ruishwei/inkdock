package org.inkdock.reader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.instrumentation
import org.inkdock.reader.BackgroundReaderTestSupport.js
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.webFor
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Selection/persistence tests use the test APK's silent TTS fixture, without speaking.
 * Returned voice/catalog data is diagnostic, not proof of the Android engine's bound identity.
 * No provider, model download, production test hook, or global system TTS setting is used.
 */
@RunWith(AndroidJUnit4::class)
class EngineSelectionDeviceTest {
    private val context get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName

    @Test fun requestedEngineIsSavedImmediatelyAndSurvivesRefreshAndActivityRecreation() =
        withVoicePreferencesRestored { prefs ->
            withReader { scenario, activity, web ->
                awaitService(activity)
                selectFixtureAndAssertImmediateSave(activity, prefs)
                awaitFixtureVoice(activity)
                assertFixtureState(settings(activity), prefs)
                assertRefreshedUi(web)

                scenario.recreate()
                val replacement = AtomicReference<MainActivity>()
                scenario.onActivity { replacement.set(it) }
                val recreated = replacement.get()
                assertNotSame("A new Activity must read the saved requested engine", activity, recreated)
                awaitService(recreated)
                awaitFixtureVoice(recreated)
                assertFixtureState(settings(recreated), prefs)
                val recreatedWeb = webFor(recreated)
                await("Recreated reader bridge is ready") {
                    js(recreatedWeb, "typeof window.setVoiceSettings==='function' && document.querySelectorAll('#content section').length>0") == "true"
                }
                assertRefreshedUi(recreatedWeb)
            }
        }

    @Test fun missingAndInstalledNonTtsPackagesCannotReplaceAValidSelection() =
        withVoicePreferencesRestored { prefs ->
            assertMissingPackage()
            @Suppress("DEPRECATION")
            val nonTtsServices = context.packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(context.packageName), 0)
            assertTrue("The installed reader app must not expose a TTS engine service", nonTtsServices.isEmpty())
            withReader { _, activity, web ->
                awaitService(activity)
                selectFixtureAndAssertImmediateSave(activity, prefs)
                awaitFixtureVoice(activity)
                for (rejected in listOf(MISSING_PACKAGE, context.packageName)) {
                    onMain { activity.Bridge().selectVoiceEngine(rejected) }
                    val rejectedState = settings(activity)
                    assertRejectedSelection(rejectedState, prefs)
                    assertTrue("Rejection must explain why the requested package was not selected", rejectedState.getString("message").contains("拒绝"))
                    val rejectionMessage = rejectedState.getString("message")
                    // A refresh must neither clear the rejection nor replace the valid selection.
                    js(web, "Native.getVoiceSettings();true")
                    await("Rejected package message reaches the reader") {
                        js(web, "document.getElementById('voiceStatus').textContent === ${JSONObject.quote(rejectionMessage)}") == "true"
                    }
                    val refreshed = settings(activity)
                    assertRejectedSelection(refreshed, prefs)
                    assertEquals("Refresh must retain the rejection explanation", rejectionMessage, refreshed.getString("message"))
                }
            }
        }

    @Test fun missingSavedEngineRemainsSelectedAndUnavailableInsteadOfDefaultFallback() =
        withVoicePreferencesRestored { prefs ->
            assertMissingPackage()
            assertTrue(prefs.edit().putString("voice.engine", MISSING_PACKAGE).commit())
            withReader { _, activity, web ->
                awaitService(activity)
                await("Missing saved engine finishes availability check") { !settings(activity).optBoolean("loading", true) }
                fun assertUnavailable() {
                    val state = settings(activity)
                    assertEquals(MISSING_PACKAGE, state.getString("enginePackage"))
                    assertEquals(MISSING_PACKAGE, prefs.getString("voice.engine", null))
                    assertFalse("Unavailable saved package must not initialize a default engine", state.getBoolean("initialized"))
                    assertEquals("", state.getString("current"))
                    assertEquals(0, state.getJSONArray("voices").length())
                    assertEquals("unavailable", state.getString("engineState"))
                    assertEquals("", state.getString("voiceCatalogSignature"))
                    assertFalse("Public Android API does not verify bound engine identity", state.getBoolean("engineBindingVerified"))
                    assertTrue("Missing saved package needs an explicit explanation", state.getString("message").isNotBlank())
                }
                assertUnavailable()
                js(web, "Native.getVoiceSettings();true")
                await("Unavailable saved engine remains visible in the reader") {
                    js(web, "document.getElementById('engineChoice').value === ${JSONObject.quote(MISSING_PACKAGE)} && document.getElementById('voiceChoice').options.length===0 && document.getElementById('saveVoice').disabled") == "true"
                }
                assertUnavailable()
            }
        }

    private fun selectFixtureAndAssertImmediateSave(activity: MainActivity, prefs: SharedPreferences) = onMain {
        // Invoke the actual JavascriptInterface implementation on the main thread. Its init
        // callback is posted, so this assertion happens before asynchronous initialization.
        activity.Bridge().selectVoiceEngine(fixturePackage)
        assertEquals("Explicit valid selection must persist before asynchronous initialization", fixturePackage, prefs.getString("voice.engine", null))
        assertEquals(fixturePackage, service(activity)!!.voiceSettings().getString("enginePackage"))
    }

    private fun awaitFixtureVoice(activity: MainActivity) = await("Selected fixture reports its offline voice", 20_000) {
        settings(activity).let {
            !it.optBoolean("loading", true) && it.optBoolean("initialized") &&
                it.optString("enginePackage") == fixturePackage && it.optString("current") == SilentFixtureTtsService.VOICE_NAME
        }
    }

    private fun assertFixtureState(state: JSONObject, prefs: SharedPreferences) {
        assertEquals(fixturePackage, state.getString("enginePackage"))
        assertEquals(fixturePackage, prefs.getString("voice.engine", null))
        assertTrue(state.getBoolean("initialized"))
        assertEquals("ready", state.getString("engineState"))
        assertEquals(SilentFixtureTtsService.VOICE_NAME, state.getString("current"))
        assertFalse("Voice catalog is not bound-identity verification", state.getBoolean("engineBindingVerified"))
        assertTrue("Ready voice catalog should provide diagnostic data", state.getString("voiceCatalogSignature").isNotEmpty())
    }

    private fun assertRejectedSelection(state: JSONObject, prefs: SharedPreferences) {
        assertEquals(fixturePackage, state.getString("enginePackage"))
        assertEquals(fixturePackage, prefs.getString("voice.engine", null))
        assertEquals("selection-rejected", state.getString("engineState"))
        assertFalse("Rejecting a switch must not leave the previous voice silently playable", state.getBoolean("initialized"))
        assertFalse(state.getBoolean("loading"))
        assertEquals("", state.getString("current"))
        assertFalse(state.getBoolean("engineBindingVerified"))
    }

    private fun assertRefreshedUi(web: WebView) {
        js(web, "Native.getVoiceSettings();true")
        await("Refreshed UI retains requested package and returned voice") {
            js(web, "document.getElementById('engineChoice').value === ${JSONObject.quote(fixturePackage)} && document.getElementById('voiceChoice').value === ${JSONObject.quote(SilentFixtureTtsService.VOICE_NAME)}") == "true"
        }
    }

    private fun awaitService(activity: MainActivity) = await("Reader's own speech Service connection") { onMain { service(activity) != null } }
    private fun settings(activity: MainActivity): JSONObject = onMain { service(activity)!!.voiceSettings() }
    private fun service(activity: MainActivity): OfflineSpeechService? =
        MainActivity::class.java.getDeclaredField("offline").apply { isAccessible = true }.get(activity) as OfflineSpeechService?

    @Suppress("DEPRECATION")
    private fun assertMissingPackage() {
        try {
            context.packageManager.getPackageInfo(MISSING_PACKAGE, 0)
            fail("Synthetic missing-package fixture unexpectedly exists")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected synthetic package. Never uninstall or change another app for this test.
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withVoicePreferencesRestored(test: (SharedPreferences) -> Unit) {
        instrumentation.waitForIdleSync()
        val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val before = prefs.all.filterKeys { it.startsWith("voice.") }
        try { test(prefs) }
        finally {
            instrumentation.waitForIdleSync()
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
            assertTrue("Restore previous voice preferences", edit.commit())
        }
    }

    companion object { private const val MISSING_PACKAGE = "org.inkdock.no_such_engine" }
}
