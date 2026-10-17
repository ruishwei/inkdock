package org.inkdock.reader

import android.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.inkdock.reader.BackgroundReaderTestSupport.assertRemains
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.js
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.systemHome
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Never approves consent, sends provider traffic, or uses a real key/document. */
@RunWith(AndroidJUnit4::class)
class CloudBackgroundConsentDeviceTest {
    @Test fun homeDismissesUnapprovedConsentAndRejectsFollowingBackgroundSegment() =
        withReader { scenario, activity, web ->
            val provider = CloudVoiceProvider.QWEN
            val keys = CloudVoiceKeyStore(activity)
            assumeTrue("Do not replace an existing provider key", keys.status(provider) == CloudVoiceKeyStatus.MISSING)
            try {
                keys.save(provider, "synthetic-instrumentation-not-a-provider-key".toCharArray())
                js(web, "window.__backgroundCloudEvents=[];const original=window.onSpeechStatus;window.onSpeechStatus=e=>{window.__backgroundCloudEvents.push(e);original(e)};Native.setSpeechSource('QWEN');true")
                val cloud = onMain {
                    val lazy = MainActivity::class.java.getDeclaredField("cloud\$delegate").apply { isAccessible = true }.get(activity) as Lazy<*>
                    lazy.value as CloudSpeechUi
                }
                js(web, "Native.previewCloudVoice();true")
                await("Unapproved native cloud consent visible") { onMain { dialog(cloud)?.isShowing == true } }
                val unapproved = onMain { dialog(cloud)!! }
                assertTrue(onMain { unapproved.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("同意发送") })
                assertNoSynthesis(cloud)

                // Actual platform Home dispatch, not a call to Activity.onPause or the JS handler.
                systemHome()
                await("Reader leaves foreground after Home") {
                    scenario.state != Lifecycle.State.RESUMED && onMain { !activity.hasWindowFocus() }
                }
                await("Consent dismissed on background transition") { onMain { !unapproved.isShowing && dialog(cloud) == null } }
                assertNoSynthesis(cloud)

                // Simulates a stale next-segment bridge call after Home. No positive consent click.
                onMain { activity.Bridge().speak("Synthetic cloud segment that must stay local.", "background-unapproved") }
                assertRemains("Background bridge must not create consent, synthesis, player, or audio", 800) {
                    onMain { dialog(cloud) == null && field(cloud, "active") == false }
                        .also { assertNoSynthesis(cloud) }
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("Reader restored after Home") { onMain { activity.hasWindowFocus() } }
                assertEquals("true", js(web, "window.__backgroundCloudEvents.every(e=>e.phase!=='queued' && e.phase!=='started')"))
                assertNull(onMain { dialog(cloud) })
                assertNoSynthesis(cloud)
            } finally {
                onMain { if (!activity.isDestroyed) activity.Bridge().stopSpeaking() }
                keys.remove(provider)
            }
            assertEquals(CloudVoiceKeyStatus.MISSING, keys.status(provider))
        }

    private fun assertNoSynthesis(cloud: CloudSpeechUi) = onMain {
        // synthesize() assigns cancellation before scheduling any provider worker.
        for (name in listOf("cancellation", "player", "audioFile")) {
            assertNull("Unexpected native cloud synthesis state: $name", field(cloud, name))
        }
    }

    private fun dialog(cloud: CloudSpeechUi): AlertDialog? = field(cloud, "dialog") as AlertDialog?
    private fun field(cloud: CloudSpeechUi, name: String): Any? =
        CloudSpeechUi::class.java.getDeclaredField(name).apply { isAccessible = true }.get(cloud)
}
