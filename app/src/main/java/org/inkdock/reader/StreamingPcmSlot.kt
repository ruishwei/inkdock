package org.inkdock.reader

/** Keeps its global slot permit until both the JNI producer and any claimed player have exited. */
internal class StreamingPcmSlot(val stream: BoundedPcmStream, private val reservation: PcmBudget.Reservation,
    private val onRelease: () -> Unit = {}) {
    private var producerFinished = false
    private var claimed = false
    private var playerFinished = false
    private var discarded = false
    private var released = false
    @Synchronized fun claimPlayback(): Boolean {
        if (discarded || claimed || released) return false
        claimed = true; return true
    }
    @Synchronized fun producerDone() { producerFinished = true; releaseIfDone() }
    @Synchronized fun finishPlayback() {
        if (claimed) { playerFinished = true; stream.cancel(); releaseIfDone() }
    }
    @Synchronized fun discard() { discarded = true; stream.cancel(); releaseIfDone() }
    private fun releaseIfDone() {
        if (!released && producerFinished && (if (claimed) playerFinished else discarded)) {
            released = true; reservation.release(); onRelease()
        }
    }
}
