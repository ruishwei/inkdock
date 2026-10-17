package org.inkdock.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM contract tests for the production cursor, two-slot pipeline, and PCM ownership budget.
 * The generator and draining player are synthetic. These tests do not instantiate the Android
 * adapter or prove AudioTrack/JNI behavior, native cancellation latency, or real-time performance.
 */
class NativePrefetchConcurrencyTest {
    @Test(timeout = 20_000) fun readyPcmOverlapsBlockedPlaybackAndOriginalDoneWaitsForFinalDrain() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B")
            val c = harness.unit("C")
            harness.speak("original", "A|B|C")
            harness.await(a.started, "A playback started")
            harness.await(a.allSamplesWritten, "A samples accepted by player")
            harness.await(b.readyDelivered, "B PCM delivered to the production pipeline")

            val whileAPlays = harness.snapshot()
            assertEquals(listOf("A", "B"), harness.generatedTexts.toList())
            assertEquals(listOf("A"), whileAPlays.playRequests)
            assertTrue(whileAPlays.readyAhead)
            assertEquals(2, whileAPlays.slots)
            assertEquals(2, harness.budget.inUse)
            assertEquals(1L, a.allowDrain.count)
            assertEquals(0L, b.resultProduced.count)
            assertEquals(PCM_SAMPLES, b.acceptedSampleCount.get())
            assertNotNull("Ready means an accepted PCM buffer exists", b.pcm)
            assertEquals(PcmBudget.SAMPLE_RATE, b.pcm!!.sampleRate)
            assertEquals(1L, c.generationStarted.count)
            assertEquals(listOf(Event("original", "started")), whileAPlays.events)

            a.allowDrain.countDown()
            harness.await(b.started, "B playback started")
            harness.await(c.readyDelivered, "C became the single replacement lookahead")
            assertTrue(harness.snapshot().readyAhead)
            assertEquals(listOf("A", "B"), harness.snapshot().playRequests)
            assertEquals(2, harness.budget.inUse)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)

            b.allowDrain.countDown()
            harness.await(c.started, "C playback started")
            harness.await(c.allSamplesWritten, "C samples accepted but not drained")
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)
            assertEquals(1L, harness.originalDone.count)
            assertEquals(1, harness.budget.inUse)

            c.allowDrain.countDown()
            harness.await(harness.originalDone, "original completion after final drain")
            val finished = harness.snapshot()
            assertEquals(listOf(Event("original", "started"), Event("original", "done")), finished.events)
            assertEquals(listOf("A", "B", "C"), finished.playRequests)
            assertEquals(0, finished.slots)
            assertEquals(0, harness.budget.inUse)
            assertEquals(2, harness.maxSlots.get())
            assertEquals(2, harness.budget.maxInUse)
            assertEquals(1, harness.maxModelConcurrency.get())
            assertEquals(1, harness.modelThreads.size)
            assertEquals(1, harness.outputThreads.size)
            assertTrue(harness.modelThreads.intersect(harness.outputThreads).isEmpty())
            assertBefore(harness.trace, "drained:C", "outer:original:done")
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000) fun cancellingReadyLookaheadRetainsOnlyPlayingClaimAndRejectsLateCallbacks() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B")
            harness.speak("original", "A|B")
            harness.await(a.started, "A playback started")
            harness.await(b.readyDelivered, "B PCM ready")
            assertEquals(2, harness.budget.inUse)

            harness.cancel()
            assertEquals(0, harness.snapshot().slots)
            assertEquals("A's playback worker still owns its claimed PCM", 1, harness.budget.inUse)
            assertFalse("Cancelled, unclaimed B was discarded", b.pcm!!.markPlaybackClaimed())
            harness.deliverLatePlaybackCallbacks(a.token, b.token)
            assertEquals(listOf("A"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)

            a.allowDrain.countDown()
            harness.await(a.playbackReturned, "cancelled A playback finally returned its claim")
            assertEquals(0, harness.budget.inUse)
            assertEquals(listOf("A"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)

            // Reusing the external request ID must not let an old generation finish the new one.
            val c = harness.unit("C")
            harness.speak("original", "C")
            harness.await(c.started, "replacement request playback started")
            harness.deliverLatePlaybackCallbacks(a.token, b.token)
            harness.deliverStalePcm(b.token)
            assertEquals(listOf("A", "C"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("original", "started"), Event("original", "started")),
                harness.snapshot().events)
            assertEquals(1, harness.snapshot().slots)
            assertEquals(1, harness.budget.inUse)

            c.allowDrain.countDown()
            harness.await(harness.originalDone, "only replacement request completes")
            assertEquals(1, harness.snapshot().events.count { it.kind == "done" })
            assertEquals(0, harness.budget.inUse)
            assertEquals(2, harness.budget.maxInUse)
            assertEquals(1, harness.maxModelConcurrency.get())
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000) fun cancelledInFlightGenerationKeepsReservationUntilItsLateResultIsDisposed() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B", holdGeneration = true, holdResult = true)
            harness.speak("original", "A|B")
            harness.await(a.started, "A playback started")
            harness.await(b.generationStarted, "B generator entered with a reservation")
            assertEquals(2, harness.budget.inUse)
            assertEquals(1L, b.resultProduced.count)

            harness.cancel()
            assertEquals(0, harness.snapshot().slots)
            assertEquals("Cancellation cannot reclaim memory a worker is still using", 2, harness.budget.inUse)
            b.allowGeneration.countDown()
            harness.await(b.resultProduced, "old generator returned actual PCM after cancellation")
            assertNotNull(b.pcm)
            assertEquals(2, harness.budget.inUse)
            assertEquals(listOf("A"), harness.snapshot().playRequests)

            // Deliberately deliver an unguarded late result to test the production core's defense.
            b.allowResultDelivery.countDown()
            harness.await(b.readyDelivered, "stale ready callback was handled")
            assertEquals(1, harness.budget.inUse)
            assertFalse(b.pcm!!.markPlaybackClaimed())
            assertEquals(0, harness.snapshot().slots)
            assertEquals(listOf("A"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)

            a.allowDrain.countDown()
            harness.await(a.playbackReturned, "old playback returned")
            assertEquals(0, harness.budget.inUse)
            assertEquals(1L, harness.originalDone.count)
            assertEquals(2, harness.budget.maxInUse)
            assertEquals(1, harness.maxModelConcurrency.get())
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000) fun modelReleaseQueuedOnModelExecutorWaitsForInFlightGeneratorToExit() {
        val harness = Harness()
        try {
            val a = harness.unit("A", holdGeneration = true)
            harness.speak("original", "A")
            harness.await(a.generationStarted, "generator entered")
            val release = harness.shutdownAndQueueModelRelease()
            assertFalse("Release is queued behind the blocked generator", release.isDone)
            assertFalse(harness.modelReleased.get())
            assertEquals(1, harness.activeModelCalls.get())
            assertEquals(1, harness.budget.inUse)

            a.allowGeneration.countDown()
            release.get(WAIT_SECONDS, TimeUnit.SECONDS)
            harness.await(a.readyDelivered, "cancelled result disposed")
            assertTrue(harness.modelReleased.get())
            assertEquals(0, harness.activeModelCalls.get())
            assertEquals(0, harness.budget.inUse)
            assertTrue(harness.snapshot().playRequests.isEmpty())
            assertTrue(harness.snapshot().events.isEmpty())
            assertBefore(harness.trace, "generate:exit:A", "model:release")
            assertEquals(1, harness.maxModelConcurrency.get())
            assertEquals(1, harness.modelThreads.size)
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000) fun followingFailureBetweenDrainAndDeferredPromotionIsDeliveredOnceWithoutRetry() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B", holdGeneration = true)
            harness.speak("first", "A")
            harness.prefetch("second", "B")
            harness.await(a.started, "A playback started")
            harness.await(b.generationStarted, "following B synthesis entered")
            a.allowDrain.countDown()
            harness.await(harness.originalDone, "A completed before deferred service promotion")
            assertEquals(listOf(Event("first", "started"), Event("first", "done")),
                harness.snapshot().events)

            // Simulate the service posting its next speak rather than calling it reentrantly.
            // A has ended, B is the pending following request, and B fails in this gap.
            harness.fail(b.token, -23)
            assertEquals(listOf(Event("first", "started"), Event("first", "done")),
                harness.snapshot().events)
            b.allowGeneration.countDown()
            harness.await(b.readyDelivered, "failed B's late result disposed")
            assertEquals(0, harness.budget.inUse)

            harness.speak("second", "B")
            harness.fail(b.token, -23)
            harness.deliverLatePlaybackCallbacks(b.token)
            val afterPromotion = harness.snapshot()
            assertEquals(listOf(Event("first", "started"), Event("first", "done"),
                Event("second", "error", -23)), afterPromotion.events)
            assertEquals("Promotion must not silently retry failed following synthesis",
                listOf("A", "B"), harness.synthesisRequests.toList())
            assertEquals(listOf("A"), afterPromotion.playRequests)
            assertEquals(0, afterPromotion.slots)
            assertEquals(0, harness.budget.inUse)
            assertEquals(1, harness.maxModelConcurrency.get())
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    private data class Event(val id: String, val kind: String, val code: Int? = null)
    private data class Snapshot(val slots: Int, val readyAhead: Boolean,
        val events: List<Event>, val playRequests: List<String>)

    private class UnitProbe(val text: String, holdGeneration: Boolean, holdResult: Boolean) {
        val allowGeneration = CountDownLatch(if (holdGeneration) 1 else 0)
        val allowResultDelivery = CountDownLatch(if (holdResult) 1 else 0)
        val allowDrain = CountDownLatch(1)
        val generationStarted = CountDownLatch(1)
        val resultProduced = CountDownLatch(1)
        val readyDelivered = CountDownLatch(1)
        val started = CountDownLatch(1)
        val allSamplesWritten = CountDownLatch(1)
        val playbackReturned = CountDownLatch(1)
        val acceptedSampleCount = AtomicInteger()
        @Volatile var pcm: PcmBuffer? = null
        @Volatile var token = 0L
    }

    private class Harness : AutoCloseable {
        private val controller = Executors.newSingleThreadExecutor { Thread(it, "contract-controller") }
        private val model = Executors.newSingleThreadExecutor { Thread(it, "contract-model") }
        private val output = Executors.newSingleThreadExecutor { Thread(it, "contract-output") }
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val units = ConcurrentHashMap<String, UnitProbe>()
        private val tokenUnits = ConcurrentHashMap<Long, UnitProbe>()
        private val generation = AtomicLong()
        private val clock = AtomicLong()
        private val closed = AtomicBoolean()
        private val events = mutableListOf<Event>() // Controller thread only.
        private val playRequests = mutableListOf<String>() // Controller thread only.
        val budget = PcmBudget()
        val maxSlots = AtomicInteger()
        val activeModelCalls = AtomicInteger()
        val maxModelConcurrency = AtomicInteger()
        val modelReleased = AtomicBoolean()
        val modelThreads = ConcurrentHashMap.newKeySet<Thread>()
        val outputThreads = ConcurrentHashMap.newKeySet<Thread>()
        val synthesisRequests = CopyOnWriteArrayList<String>()
        val generatedTexts = CopyOnWriteArrayList<String>()
        val trace = CopyOnWriteArrayList<String>()
        val originalDone = CountDownLatch(1)

        private val pipeline: TwoSlotSpeechPipeline<PcmBuffer> = TwoSlotSpeechPipeline(object : TwoSlotSpeechPipeline.Port<PcmBuffer> {
            override fun synthesize(token: Long, text: String) = generate(token, text)
            override fun play(token: Long, audio: PcmBuffer) = playPcm(token, audio)
            override fun directSpeak(token: Long, text: String) { error("Unexpected direct fallback") }
            override fun cancelSynthesis() { generation.incrementAndGet() }
            override fun dispose(audio: PcmBuffer) = audio.discard()
            override fun event(id: String, event: String, code: Int?) = cursor.unitEvent(id, event, code)
            override fun diagnostic(value: String) { }
        }, clock::incrementAndGet)

        private val cursor = SubsentenceSpeechCursor({ it.split('|') }, object : SubsentenceSpeechCursor.Sink {
            override fun speakUnit(id: String, text: String) { pipeline.speak(id, text); checkBounds() }
            override fun prefetchUnit(id: String, text: String) { pipeline.prefetch(id, text); checkBounds() }
            override fun cancelUnits() { pipeline.cancel(); checkBounds() }
            override fun event(id: String, event: String, code: Int?) {
                events += Event(id, event, code)
                trace += "outer:$id:$event"
                if (event == "done") originalDone.countDown()
            }
        })

        fun unit(text: String, holdGeneration: Boolean = false, holdResult: Boolean = false): UnitProbe =
            units.computeIfAbsent(text) { UnitProbe(text, holdGeneration, holdResult) }

        fun speak(id: String, text: String) = onController { assertTrue(cursor.speak(id, text)) }
        fun prefetch(id: String, text: String) = onController { cursor.prefetch(id, text) }
        fun cancel() = onController { cursor.cancel() }
        fun fail(token: Long, code: Int) = onController { pipeline.terminalFailure(token, "synthetic-failure", code) }
        fun snapshot() = onController {
            Snapshot(pipeline.slotCount, pipeline.readyAhead, events.toList(), playRequests.toList())
        }

        fun deliverLatePlaybackCallbacks(vararg tokens: Long) = onController {
            tokens.forEach { pipeline.playbackStarted(it); pipeline.playbackDone(it) }
        }

        fun deliverStalePcm(token: Long) = onController {
            val reservation = requireNotNull(budget.reserve { false })
            val pcm = try { reservation.accept(floatArrayOf(0.25f), PcmBudget.SAMPLE_RATE) }
                finally { reservation.release() }
            pipeline.synthesisDone(token)
            pipeline.ready(token, pcm, 1)
            assertFalse("Stale PCM must be discarded rather than played", pcm.markPlaybackClaimed())
        }

        private fun generate(token: Long, text: String) {
            synthesisRequests += text
            val probe = unit(text)
            probe.token = token
            tokenUnits[token] = probe
            val epoch = generation.get()
            dispatch(model) {
                if (closed.get() || epoch != generation.get()) return@dispatch
                val reservation = budget.reserve { closed.get() || epoch != generation.get() } ?: return@dispatch
                var pcm: PcmBuffer? = null
                val count = activeModelCalls.incrementAndGet()
                maxModelConcurrency.updateAndGet { maxOf(it, count) }
                modelThreads += Thread.currentThread()
                try {
                    check(!modelReleased.get())
                    assertEquals(1, count)
                    trace += "generate:start:$text"
                    probe.generationStarted.countDown()
                    await(probe.allowGeneration, "release synthetic generation $text")
                    // Small, nonempty 24 kHz PCM. Accept validates output and transfers the permit.
                    pcm = reservation.accept(FloatArray(PCM_SAMPLES) { sampleFor(text) }, PcmBudget.SAMPLE_RATE)
                    probe.pcm = pcm
                    probe.acceptedSampleCount.set(PCM_SAMPLES)
                    generatedTexts += text
                    probe.resultProduced.countDown()
                    await(probe.allowResultDelivery, "release synthetic result $text")
                } catch (failure: Throwable) {
                    pcm?.discard()
                    throw failure
                } finally {
                    activeModelCalls.decrementAndGet()
                    trace += "generate:exit:$text"
                    reservation.release()
                }
                val ready = requireNotNull(pcm)
                // Intentionally no epoch guard here: cancelled/stale deliveries must also be
                // rejected by the production pipeline. The Android adapter has an extra guard.
                dispatch(controller) {
                    pipeline.synthesisDone(token)
                    pipeline.ready(token, ready, PCM_SAMPLES * 1000L / PcmBudget.SAMPLE_RATE)
                    checkBounds()
                    probe.readyDelivered.countDown()
                }
            }
        }

        private fun playPcm(token: Long, pcm: PcmBuffer) {
            val probe = requireNotNull(tokenUnits[token])
            playRequests += probe.text
            assertTrue(pcm.markPlaybackClaimed())
            val epoch = generation.get()
            dispatch(output) {
                outputThreads += Thread.currentThread()
                try {
                    if (closed.get() || epoch != generation.get()) return@dispatch
                    val samples = requireNotNull(pcm.samples)
                    assertEquals(PCM_SAMPLES, samples.size)
                    assertTrue(samples.all { it == sampleFor(probe.text) })
                    dispatch(controller) {
                        pipeline.playbackStarted(token)
                        probe.started.countDown()
                    }
                    // A real player accepting the final write is not evidence of audio drain.
                    probe.allSamplesWritten.countDown()
                    await(probe.allowDrain, "drain synthetic playback ${probe.text}")
                    trace += "drained:${probe.text}"
                } finally {
                    pcm.finishPlayback()
                }
                // Deliver even after cancellation so generation-token filtering is exercised.
                dispatch(controller) {
                    pipeline.playbackDone(token)
                    checkBounds()
                    probe.playbackReturned.countDown()
                }
            }
        }

        fun shutdownAndQueueModelRelease(): Future<*> {
            onController { closed.set(true); cursor.cancel() }
            val release = model.submit {
                modelThreads += Thread.currentThread()
                assertEquals(0, activeModelCalls.get())
                trace += "model:release"
                modelReleased.set(true)
            }
            model.shutdown()
            return release
        }

        private fun checkBounds() {
            assertEquals("Pipeline/cursor access is serialized", "contract-controller", Thread.currentThread().name)
            val slots = pipeline.slotCount
            maxSlots.updateAndGet { maxOf(it, slots) }
            assertTrue("At most current plus next", slots in 0..2)
            assertTrue("Reservations and retained PCM share the same budget", budget.inUse in 0..2)
        }

        private fun dispatch(executor: ExecutorService, block: () -> Unit) {
            executor.execute {
                try { block() } catch (failure: Throwable) { failures += failure }
            }
        }

        private fun <T> onController(block: () -> T): T {
            val value = controller.submit<T> { block().also { checkBounds() } }
                .get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertHealthy()
            return value
        }

        fun await(latch: CountDownLatch, description: String) {
            val completed = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            assertHealthy()
            assertTrue("Timed out waiting to $description", completed)
        }

        fun assertHealthy() {
            failures.peek()?.let { throw AssertionError("Asynchronous contract worker failed", it) }
        }

        override fun close() {
            try {
                // Keep the controller alive until workers have delivered their final callbacks.
                controller.submit { closed.set(true); cursor.cancel() }.get(WAIT_SECONDS, TimeUnit.SECONDS)
            } finally {
                units.values.forEach {
                    it.allowGeneration.countDown()
                    it.allowResultDelivery.countDown()
                    it.allowDrain.countDown()
                }
                model.shutdown()
                output.shutdown()
                try {
                    assertTrue("Model worker terminated", model.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
                    assertTrue("Output worker terminated", output.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
                    controller.submit { }.get(WAIT_SECONDS, TimeUnit.SECONDS)
                } finally {
                    model.shutdownNow()
                    output.shutdownNow()
                    controller.shutdown()
                    if (!controller.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) controller.shutdownNow()
                }
            }
            assertHealthy()
            assertEquals("Every retained PCM/reservation is released", 0, budget.inUse)
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
        private const val PCM_SAMPLES = 480
        private fun sampleFor(text: String) = text.first().code / 128f
        private fun assertBefore(trace: List<String>, first: String, second: String) {
            val before = trace.indexOf(first)
            val after = trace.indexOf(second)
            assertTrue("Expected $first before $second; trace=$trace", before >= 0 && after > before)
        }
    }
}
