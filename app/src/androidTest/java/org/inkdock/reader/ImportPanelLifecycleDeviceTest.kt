package org.inkdock.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.inkdock.reader.BackgroundReaderTestSupport.await
import org.inkdock.reader.BackgroundReaderTestSupport.js
import org.inkdock.reader.BackgroundReaderTestSupport.onMain
import org.inkdock.reader.BackgroundReaderTestSupport.systemBack
import org.inkdock.reader.BackgroundReaderTestSupport.withReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the system document picker, then cancels. Never reads or imports a user file. */
@RunWith(AndroidJUnit4::class)
class ImportPanelLifecycleDeviceTest {
    @Test fun moreImportClosesPanelAndPickerCancelRestoresUnobstructedReader() =
        withReader { scenario, activity, web ->
            repeat(2) {
                js(web, "document.getElementById('moreButton').click();true")
                await("More panel opened") { js(web, "!document.getElementById('morePanel').hidden") == "true" }
                js(web, "document.getElementById('open').click();true")
                await("System document picker takes foreground") {
                    scenario.state != Lifecycle.State.RESUMED && onMain { !activity.hasWindowFocus() }
                }
                // Back is delivered to the actual system picker, not the reader's panel callback.
                systemBack()
                await("Picker cancellation returns original reader") {
                    scenario.state == Lifecycle.State.RESUMED && onMain { activity.hasWindowFocus() }
                }
                scenario.onActivity { current ->
                    assertSame("Picker cancellation must retain the reader Activity", activity, current)
                    assertFalse(current.isFinishing)
                    assertFalse(current.isDestroyed)
                }
                assertEquals("true", js(web, "['morePanel','voicePanel','tocPanel'].every(id=>document.getElementById(id).hidden)"))
                assertEquals("true", js(web, "document.getElementById('content').textContent.includes('Synthetic background reader fixture')"))
                assertFalse("Native panel state must clear before external picker", onMain {
                    MainActivity::class.java.getDeclaredField("panelOpen").apply { isAccessible = true }.getBoolean(activity)
                })
            }
        }
}
