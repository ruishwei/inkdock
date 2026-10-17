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
 * Composes the production streaming pipeline, PCM ring, ownership slot, budget, and cursor.
 * One synthetic model worker and one synthetic output worker communicate through a serialized
 * controller. Latches represent native callbacks and device drain; no Android, JNI, model files,
 * network, or timing sleeps are involved. These are lifecycle contracts, not latency benchmarks.
 */
class StreamingPrefetchConcurrencyTest {
    @Test(timeout = 20_000)
    fun firstPcmPlaysBeforeGenerationEndsAndTemporaryEmptyWaitsForEofAndDeviceDrain() {
        val harness = Harness()
        try {
            val a = harness.unit("A", chunks = listOf(
                floatArrayOf(-0.0f, Float.MIN_VALUE, -0.75f),
                floatArrayOf(0.25f, 0.5f, -Float.MIN_VALUE)
            ), holdContinuation = true, holdEof = true)
            val b = harness.unit("B", chunks = listOf(floatArrayOf(0.125f, -0.125f)))
            harness.speak("original", "A|B")
            harness.await(a.emptyReadEntered, "A player waits for another callback")

            val betweenCallbacks = harness.snapshot()
            assertEquals(listOf("A"), betweenCallbacks.playRequests)
            assertEquals(listOf(Event("original", "started")), betweenCallbacks.events)
            assertEquals(1L, a.generationReturned.count)
            assertEquals(1L, a.eofRead.count)
            assertEquals(1L, a.playbackReturned.count)
            assertEquals(1L, b.generationStarted.count)
            assertEquals(1, harness.activeModelCalls.get())
            assertEquals(0, a.stream!!.queuedSamples)
            assertEquals(bits(a.chunks.first()), a.playedBits.toList())
            assertBefore(harness.trace, "first-pcm:A", "play-start:A")

            a.allowContinuation.countDown()
            harness.await(a.writeReturned.last(), "A's final callback copied")
            // Even all currently available samples are insufficient: explicit generation EOF
            // and the output device's later drain must both precede the original done event.
            assertEquals(1L, a.eofRead.count)
            assertEquals(1L, harness.originalDone.count)
            assertEquals(1L, b.generationStarted.count)
            a.allowEof.countDown()
            harness.await(a.eofRead, "A's explicit EOF reaches its player")
            harness.await(b.generationReturned, "B generates while A's device still drains")
            val beforeDrain = harness.snapshot()
            assertTrue(beforeDrain.readyAhead)
            assertTrue(beforeDrain.prefixAhead)
            assertEquals(listOf("A"), beforeDrain.playRequests)
            assertEquals(1L, a.allowDrain.count)
            assertEquals(1L, harness.originalDone.count)
            assertEquals(2, harness.budget.inUse)
            assertBefore(harness.trace, "play-start:A", "generate:eof:A")
            assertBefore(harness.trace, "generate:eof:A", "generate:start:B")

            a.allowDrain.countDown()
            harness.await(b.eofRead, "promoted B reaches EOF")
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)
            assertEquals(1L, harness.originalDone.count)
            b.allowDrain.countDown()
            harness.await(harness.originalDone, "original completion after B drains")
            harness.assertPlayedExactly(a, b)
            assertEquals(listOf(Event("original", "started"), Event("original", "done")),
                harness.snapshot().events)
            assertBefore(harness.trace, "generate:start:B", "drained:A")
            assertBefore(harness.trace, "drained:B", "outer:original:done")
            harness.assertWorkersAndBounds()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000)
    fun fullLookaheadRingBlocksOnlyProducerUntilPromotionFreesItWithoutLosingSamples() {
        val harness = Harness()
        try {
            // Every callback exceeds the entire ring, including current A. First-PCM delivery
            // must therefore happen inside write(), before its oversized callback returns.
            val a = harness.unit("A", chunks = listOf(samples(11, 1)))
            val b = harness.unit("B", chunks = listOf(samples(29, 2)))
            val c = harness.unit("C", chunks = listOf(samples(17, 3)))
            harness.speak("original", "A|B|C")
            harness.await(a.eofRead, "A finishes submitting samples but holds device drain")
            harness.await(b.fullWriteEntered, "B producer reaches its full lookahead ring")
            harness.await(b.readyDelivered, "B's first PCM is retained as lookahead")

            val fullAhead = harness.snapshot()
            assertTrue(fullAhead.prefixAhead)
            assertFalse("A prefix is not generation EOF", fullAhead.readyAhead)
            assertEquals(listOf("A"), fullAhead.playRequests)
            assertEquals(2, fullAhead.slots)
            assertEquals(RING_SAMPLES, b.stream!!.queuedSamples)
            assertEquals(RING_SAMPLES, b.stream!!.maxQueuedSamples)
            assertEquals(1L, b.writeReturned.single().count)
            assertEquals(1L, b.generationReturned.count)
            assertEquals(1L, c.generationStarted.count)
            assertEquals(2, harness.budget.inUse)
            assertEquals(1L, a.allowDrain.count)
            assertBefore(harness.trace, "generate:eof:A", "generate:start:B")

            a.allowDrain.countDown()
            harness.await(b.eofRead, "B promotion drains its ring and unblocks generation")
            harness.await(c.fullWriteEntered, "C replaces B as the only full lookahead")
            harness.await(c.readyDelivered, "C first PCM delivery")
            assertEquals(listOf("A", "B"), harness.snapshot().playRequests)
            assertEquals(2, harness.budget.inUse)
            assertEquals(1L, c.writeReturned.single().count)
            assertEquals(1L, harness.originalDone.count)
            b.allowDrain.countDown()
            harness.await(c.eofRead, "C promotion frees its producer")
            assertEquals(1L, harness.originalDone.count)
            c.allowDrain.countDown()
            harness.await(harness.originalDone, "all callbacks and final device drain complete")

            harness.assertPlayedExactly(a, b, c)
            listOf(a, b, c).forEach {
                assertEquals(RING_SAMPLES, it.stream!!.maxQueuedSamples)
                assertEquals(it.expectedBits.size.toLong(), it.stream!!.totalWritten)
                assertEquals(it.expectedBits.size.toLong(), it.stream!!.totalRead)
            }
            assertEquals(listOf("A", "B", "C"), harness.generatedTexts.toList())
            assertBefore(harness.trace, "generate:start:B", "drained:A")
            assertBefore(harness.trace, "generate:start:C", "drained:B")
            assertEquals(listOf(Event("original", "started"), Event("original", "done")),
                harness.snapshot().events)
            harness.assertWorkersAndBounds()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000)
    fun cancellationWakesFullRingButModelReleaseWaitsForCancelledNativeCallToReturn() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B", chunks = listOf(samples(31, 4)), holdNativeExit = true)
            harness.speak("original", "A|B")
            harness.await(a.eofRead, "A holds its claimed playback while draining")
            harness.await(b.fullWriteEntered, "B native callback is blocked by a full ring")
            harness.await(b.readyDelivered, "B prefix callback delivered")
            val release = harness.shutdownAndQueueModelRelease()
            harness.await(b.writeReturned.single(), "cancellation wakes B's blocked callback")

            assertFalse(b.allWritesAccepted.get())
            assertEquals(0, b.stream!!.queuedSamples)
            assertEquals(-1, b.stream!!.read(FloatArray(1)))
            assertEquals(0, harness.snapshot().slots)
            assertEquals("A's claimant and B's native caller still own their permits", 2, harness.budget.inUse)
            assertEquals(1, harness.activeModelCalls.get())
            assertFalse("Model release must be queued behind the native call", release.isDone)
            assertFalse(harness.modelReleased.get())
            harness.deliverLateCallbacks(a, b)
            assertEquals(listOf("A"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)

            b.allowNativeExit.countDown()
            release.get(WAIT_SECONDS, TimeUnit.SECONDS)
            harness.await(b.generationReturned, "cancelled native call releases its own permit")
            assertTrue(harness.modelReleased.get())
            assertEquals(0, harness.activeModelCalls.get())
            assertEquals("Cancelled playback remains owned until its worker exits", 1, harness.budget.inUse)
            assertBefore(harness.trace, "generate:exit:B", "model:release")
            a.allowDrain.countDown()
            harness.await(a.playbackReturned, "cancelled A finally returns its playback claim")
            harness.deliverLateCallbacks(a, b)
            assertEquals(0, harness.budget.inUse)
            assertEquals(1L, harness.originalDone.count)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)
            harness.assertWorkersAndBounds()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000)
    fun twoCancelledPlaybackClaimsKeepGlobalPermitsAndCancellingReplacementWakesBudgetWait() {
        val harness = Harness()
        try {
            val a = harness.unit("A")
            val b = harness.unit("B")
            val c = harness.unit("C")
            harness.speak("reused", "A")
            harness.await(a.eofRead, "A owns the output worker until device drain")
            harness.speak("reused", "B")
            harness.await(b.readyDelivered, "B claims playback while queued behind cancelled A")
            harness.await(b.generationReturned, "B generation finishes with its queued claim retained")
            assertEquals(listOf("A", "B"), harness.snapshot().playRequests)
            assertEquals(1L, b.started.count)
            assertEquals(2, harness.budget.inUse)

            harness.speak("reused", "C")
            harness.await(c.fullBudgetObserved, "C attempts reservation while both cancelled claims persist")
            assertEquals("Logical cancellation cannot free a running or queued playback claim", 2,
                harness.budget.inUse)
            assertEquals(1L, c.reservationReturned.count)
            assertEquals(1L, c.generationStarted.count)
            assertNull("No third ring may be allocated before a permit is reserved", c.stream)
            harness.cancel()
            harness.await(c.reservationReturned, "cancellation wakes the global permit waiter")
            harness.await(c.jobReturned, "cancelled replacement exits without allocating PCM")
            assertEquals(1L, c.generationStarted.count)
            assertNull(c.stream)
            assertEquals(2, harness.budget.inUse)
            harness.deliverLateCallbacks(a, b, c)
            assertEquals(0, harness.snapshot().slots)
            assertEquals(listOf(Event("reused", "started")), harness.snapshot().events)

            a.allowDrain.countDown()
            harness.await(a.playbackReturned, "A releases its cancelled running claim")
            harness.await(b.playbackReturned, "B releases its cancelled queued claim without playing")
            assertEquals(0, harness.budget.inUse)
            assertEquals(1L, b.started.count)
            assertEquals(1L, harness.originalDone.count)

            // Reusing the public ID must not make old tokens advance the replacement request.
            val d = harness.unit("D")
            harness.speak("reused", "D")
            harness.await(d.eofRead, "new request plays normally after old claims release")
            harness.deliverLateCallbacks(a, b, c)
            assertEquals(listOf("A", "B", "D"), harness.snapshot().playRequests)
            assertEquals(listOf(Event("reused", "started"), Event("reused", "started")),
                harness.snapshot().events)
            assertEquals(1, harness.snapshot().slots)
            assertEquals(1, harness.budget.inUse)
            d.allowDrain.countDown()
            harness.await(harness.originalDone, "only replacement D completes")
            harness.assertPlayedExactly(a, d)
            assertTrue(b.playedBits.isEmpty())
            assertTrue(c.playedBits.isEmpty())
            assertEquals(1, harness.snapshot().events.count { it.kind == "done" })
            assertEquals(listOf("A", "B", "D"), harness.generatedTexts.toList())
            harness.assertWorkersAndBounds()
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 20_000)
    fun cancellationWakesTemporarilyEmptyPlayerWithoutReleasingStillRunningProducer() {
        val harness = Harness()
        try {
            val a = harness.unit("A", chunks = listOf(samples(2, 5), samples(3, 6)),
                holdContinuation = true)
            harness.speak("original", "A")
            harness.await(a.emptyReadEntered, "player blocks between native callbacks")
            harness.cancel()
            harness.await(a.playbackReturned, "cancelled empty ring wakes its player")
            assertEquals(1, harness.budget.inUse)
            assertEquals(1, harness.activeModelCalls.get())
            assertEquals(1L, a.generationReturned.count)
            assertEquals(1L, harness.originalDone.count)
            a.allowContinuation.countDown()
            harness.await(a.generationReturned, "producer exits and releases final permit")
            harness.deliverLateCallbacks(a)
            assertEquals(0, harness.budget.inUse)
            assertEquals(listOf(Event("original", "started")), harness.snapshot().events)
            assertEquals(bits(a.chunks.first()), a.playedBits.toList())
            assertFalse(a.allWritesAccepted.get())
            harness.assertHealthy()
        } finally {
            harness.close()
        }
    }

    private data class Event(val id: String, val kind: String, val code: Int? = null)
    private data class Snapshot(val slots: Int, val readyAhead: Boolean, val prefixAhead: Boolean,
        val events: List<Event>, val playRequests: List<String>)

    private class UnitProbe(val text: String, val chunks: List<FloatArray>, holdContinuation: Boolean,
        holdEof: Boolean, holdNativeExit: Boolean) {
        val allowContinuation = CountDownLatch(if (holdContinuation) 1 else 0)
        val allowEof = CountDownLatch(if (holdEof) 1 else 0)
        val allowNativeExit = CountDownLatch(if (holdNativeExit) 1 else 0)
        val allowDrain = CountDownLatch(1)
        val fullBudgetObserved = CountDownLatch(1)
        val reservationReturned = CountDownLatch(1)
        val generationStarted = CountDownLatch(1)
        val generationReturned = CountDownLatch(1)
        val jobReturned = CountDownLatch(1)
        val readyDelivered = CountDownLatch(1)
        val started = CountDownLatch(1)
        val fullWriteEntered = CountDownLatch(1)
        val emptyReadEntered = CountDownLatch(1)
        val eofRead = CountDownLatch(1)
        val playbackReturned = CountDownLatch(1)
        val writeReturned = chunks.map { CountDownLatch(1) }
        val allWritesAccepted = AtomicBoolean(true)
        val playedBits = CopyOnWriteArrayList<Int>()
        val expectedBits get() = chunks.flatMap(::bits)
        @Volatile var stream: BoundedPcmStream? = null
        @Volatile var slot: StreamingPcmSlot? = null
        @Volatile var token = 0L
    }

    private class Harness : AutoCloseable {
        private val controller = Executors.newSingleThreadExecutor { Thread(it, "stream-controller") }
        private val model = Executors.newSingleThreadExecutor { Thread(it, "stream-model") }
        private val output = Executors.newSingleThreadExecutor { Thread(it, "stream-output") }
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val units = ConcurrentHashMap<String, UnitProbe>()
        private val tokenUnits = ConcurrentHashMap<Long, UnitProbe>()
        private val retained = ConcurrentHashMap<Long, StreamingPcmSlot>()
        private val generation = AtomicLong()
        private val clock = AtomicLong()
        private val closed = AtomicBoolean()
        private val events = mutableListOf<Event>() // Serialized controller only.
        private val playRequests = mutableListOf<String>() // Serialized controller only.
        val budget = PcmBudget(2)
        val activeModelCalls = AtomicInteger()
        val maxModelConcurrency = AtomicInteger()
        val maxSlots = AtomicInteger()
        val modelReleased = AtomicBoolean()
        val modelThreads = ConcurrentHashMap.newKeySet<Thread>()
        val outputThreads = ConcurrentHashMap.newKeySet<Thread>()
        val generatedTexts = CopyOnWriteArrayList<String>()
        val trace = CopyOnWriteArrayList<String>()
        val originalDone = CountDownLatch(1)

        private val pipeline: StreamingSpeechPipeline<StreamingPcmSlot> = StreamingSpeechPipeline(object : StreamingSpeechPipeline.Port<StreamingPcmSlot> {
            override fun synthesize(token: Long, text: String) = generate(token, text)
            override fun play(token: Long, audio: StreamingPcmSlot) = playPcm(token, audio)
            override fun cancelSynthesis() {
                generation.incrementAndGet()
                retained.values.forEach { it.discard() }
            }
            override fun dispose(audio: StreamingPcmSlot) = audio.discard()
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

        fun unit(text: String, chunks: List<FloatArray> = listOf(samples(2, text.first().code)),
            holdContinuation: Boolean = false, holdEof: Boolean = false,
            holdNativeExit: Boolean = false): UnitProbe = units.computeIfAbsent(text) {
                UnitProbe(text, chunks, holdContinuation, holdEof, holdNativeExit)
            }

        fun speak(id: String, text: String) = onController { assertTrue(cursor.speak(id, text)) }
        fun cancel() = onController { cursor.cancel() }
        fun snapshot() = onController {
            Snapshot(pipeline.slotCount, pipeline.readyAhead, pipeline.prefixAhead,
                events.toList(), playRequests.toList())
        }
        fun deliverLateCallbacks(vararg probes: UnitProbe) = onController {
            probes.forEach {
                pipeline.synthesisStarted(it.token)
                it.slot?.let { slot -> pipeline.streamReady(it.token, slot) }
                pipeline.synthesisFinished(it.token)
                pipeline.playbackStarted(it.token)
                pipeline.playbackDone(it.token)
            }
        }

        private fun alive(epoch: Long) = !closed.get() && epoch == generation.get()

        private fun generate(token: Long, text: String) {
            val probe = unit(text)
            probe.token = token
            tokenUnits[token] = probe
            val epoch = generation.get()
            dispatch(model) {
                modelThreads += Thread.currentThread()
                try {
                    val reservation = try {
                        budget.reserve {
                            val cancelled = !alive(epoch)
                            if (!cancelled && budget.inUse == budget.capacity) probe.fullBudgetObserved.countDown()
                            cancelled
                        }
                    } finally { probe.reservationReturned.countDown() }
                    if (reservation == null) return@dispatch
                    var slot: StreamingPcmSlot? = null
                    var enteredModel = false
                    try {
                        lateinit var audio: StreamingPcmSlot
                        val stream = BoundedPcmStream(RING_SAMPLES, isCancelled = {
                            val current = probe.stream
                            // These latches observe the actual production ring's next wait decision.
                            // No consumer exists for lookahead, so a full-ring writer cannot escape
                            // until promotion or cancellation, regardless of scheduler interleaving.
                            if (Thread.currentThread().name == "stream-model" &&
                                current?.queuedSamples == RING_SAMPLES) probe.fullWriteEntered.countDown()
                            if (Thread.currentThread().name == "stream-output" &&
                                current?.queuedSamples == 0 && probe.playedBits.isNotEmpty()) {
                                probe.emptyReadEntered.countDown()
                            }
                            !alive(epoch)
                        }, onFirstPcm = {
                            trace += "first-pcm:$text"
                            dispatch(controller) {
                                // Deliberately allow late delivery; the core must reject stale tokens.
                                pipeline.streamReady(token, audio)
                                checkBounds()
                                probe.readyDelivered.countDown()
                            }
                        })
                        audio = StreamingPcmSlot(stream, reservation) { retained.remove(token) }
                        slot = audio
                        probe.stream = stream
                        probe.slot = audio
                        retained[token] = audio
                        if (!alive(epoch)) { audio.discard(); return@dispatch }
                        val count = activeModelCalls.incrementAndGet()
                        enteredModel = true
                        maxModelConcurrency.updateAndGet { maxOf(it, count) }
                        check(!modelReleased.get())
                        assertEquals(1, count)
                        generatedTexts += text
                        trace += "generate:start:$text"
                        probe.generationStarted.countDown()
                        dispatch(controller) { pipeline.synthesisStarted(token) }
                        for ((index, chunk) in probe.chunks.withIndex()) {
                            if (index > 0) await(probe.allowContinuation, "allow next native callback $text")
                            val accepted = stream.write(chunk)
                            if (!accepted) probe.allWritesAccepted.set(false)
                            probe.writeReturned[index].countDown()
                            if (!accepted) break
                        }
                        if (probe.allWritesAccepted.get()) {
                            await(probe.allowEof, "allow native EOF $text")
                            if (alive(epoch)) {
                                trace += "generate:eof:$text"
                                stream.finish()
                                dispatch(controller) { pipeline.synthesisFinished(token); checkBounds() }
                            }
                        }
                    } finally {
                        // A native callback returning does not mean the surrounding JNI function
                        // has returned. Shutdown must keep the model leased through this gate.
                        await(probe.allowNativeExit, "allow native call exit $text")
                        if (enteredModel) {
                            activeModelCalls.decrementAndGet()
                            trace += "generate:exit:$text"
                        }
                        if (!alive(epoch) || !probe.allWritesAccepted.get()) slot?.discard()
                        if (slot == null) reservation.release() else slot.producerDone()
                        probe.generationReturned.countDown()
                    }
                } finally { probe.jobReturned.countDown() }
            }
        }

        private fun playPcm(token: Long, slot: StreamingPcmSlot) {
            val probe = requireNotNull(tokenUnits[token])
            playRequests += probe.text
            assertTrue(slot.claimPlayback())
            val epoch = generation.get()
            dispatch(output) {
                outputThreads += Thread.currentThread()
                try {
                    if (alive(epoch)) {
                        dispatch(controller) {
                            trace += "play-start:${probe.text}"
                            pipeline.playbackStarted(token)
                            probe.started.countDown()
                        }
                        val scratch = FloatArray(3)
                        while (true) {
                            val count = slot.stream.read(scratch)
                            if (count < 0) break
                            if (count == 0) {
                                probe.eofRead.countDown()
                                await(probe.allowDrain, "allow device drain ${probe.text}")
                                trace += "drained:${probe.text}"
                                break
                            }
                            for (index in 0 until count) probe.playedBits += scratch[index].toRawBits()
                        }
                    }
                } finally { slot.finishPlayback() }
                dispatch(controller) {
                    // Exercise core cancellation defense even if the output result arrived late.
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
            assertEquals("stream-controller", Thread.currentThread().name)
            maxSlots.updateAndGet { maxOf(it, pipeline.slotCount) }
            assertTrue("At most current plus next logical streams", pipeline.slotCount in 0..2)
            assertTrue("Cancelled retained claims still count against the global budget", budget.inUse in 0..2)
        }

        fun assertPlayedExactly(vararg probes: UnitProbe) {
            probes.forEach { assertEquals("Every original float bit survives ${it.text}",
                it.expectedBits, it.playedBits.toList()) }
        }

        fun assertWorkersAndBounds() {
            assertEquals(2, budget.maxInUse)
            assertEquals(1, maxModelConcurrency.get())
            assertEquals(1, modelThreads.size)
            assertEquals(1, outputThreads.size)
            assertTrue(modelThreads.intersect(outputThreads).isEmpty())
            assertTrue(maxSlots.get() <= 2)
            assertHealthy()
        }

        private fun dispatch(executor: ExecutorService, block: () -> Unit) {
            executor.execute { try { block() } catch (failure: Throwable) { failures += failure } }
        }

        private fun <T> onController(block: () -> T): T {
            val result = controller.submit<T> { block().also { checkBounds() } }
                .get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertHealthy()
            return result
        }

        fun await(latch: CountDownLatch, description: String) {
            val completed = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            assertHealthy()
            assertTrue("Timed out waiting for $description", completed)
        }

        fun assertHealthy() {
            failures.peek()?.let { throw AssertionError("Asynchronous streaming worker failed", it) }
        }

        override fun close() {
            try {
                controller.submit { closed.set(true); cursor.cancel() }.get(WAIT_SECONDS, TimeUnit.SECONDS)
            } finally {
                units.values.forEach {
                    it.allowContinuation.countDown()
                    it.allowEof.countDown()
                    it.allowNativeExit.countDown()
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
            assertEquals("All PCM ownership is released", 0, budget.inUse)
            assertTrue("No cancelled stream remains retained", retained.isEmpty())
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
        private const val RING_SAMPLES = 4
        private fun samples(count: Int, seed: Int) = FloatArray(count) { index ->
            ((index * 7 + seed * 3) % 31 - 15) / 32f
        }
        private fun bits(samples: FloatArray) = samples.map(Float::toRawBits)
        private fun assertBefore(trace: List<String>, first: String, second: String) {
            val before = trace.indexOf(first)
            val after = trace.indexOf(second)
            assertTrue("Expected $first before $second; trace=$trace", before >= 0 && after > before)
        }
    }
}
