package org.inkdock.reader

/** No exception may cross the JNI boundary, including a ring notification/interrupt failure. */
internal class StreamingSynthesisCallback(private val stream: BoundedPcmStream,
    private val alive: () -> Boolean, private val now: () -> Long,
    private val maxSamples: Long = PcmBudget.MAX_SAMPLES.toLong()) : (FloatArray) -> Int {
    var firstCallbackAt = -1L; private set
    var samplesReceived = 0L; private set
    var callbackCount = 0L; private set
    var failed = false; private set
    override fun invoke(samples: FloatArray): Int = try {
        if (firstCallbackAt < 0) firstCallbackAt = now()
        callbackCount++
        samplesReceived += samples.size
        when {
            !alive() -> 0
            samplesReceived > maxSamples || samples.any { !it.isFinite() } -> abort()
            stream.write(samples) -> 1
            else -> abort()
        }
    } catch (_: Throwable) { abort() }
    private fun abort(): Int { failed = true; stream.cancel(); return 0 }
    fun acceptsFinal(sampleRate: Int, finalSamples: Int): Boolean = !failed &&
        sampleRate == PcmBudget.SAMPLE_RATE && samplesReceived in 1..maxSamples &&
        samplesReceived == stream.totalWritten && samplesReceived == finalSamples.toLong()
}
