package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class StreamingPcmSlotTest {
    @Test fun claimedCancellationRetainsPermitUntilBothWorkersExit() {
        val budget = PcmBudget(); val slot = StreamingPcmSlot(BoundedPcmStream(4), budget.reserve { false }!!)
        assertTrue(slot.claimPlayback()); slot.discard(); slot.producerDone()
        assertEquals(1, budget.inUse); slot.finishPlayback(); assertEquals(0, budget.inUse)
        slot.finishPlayback(); slot.producerDone(); slot.discard(); assertEquals(0, budget.inUse)
    }
    @Test fun playerCanFinishBeforeProducerWithoutPrematureRelease() {
        val budget = PcmBudget(); val slot = StreamingPcmSlot(BoundedPcmStream(4), budget.reserve { false }!!)
        assertTrue(slot.claimPlayback()); slot.finishPlayback(); assertEquals(1, budget.inUse)
        slot.producerDone(); assertEquals(0, budget.inUse)
    }
    @Test fun unclaimedReadyStreamStaysReservedUntilDiscardOrPlayback() {
        val budget = PcmBudget(); val slot = StreamingPcmSlot(BoundedPcmStream(4), budget.reserve { false }!!)
        slot.producerDone(); assertEquals(1, budget.inUse)
        slot.discard(); assertEquals(0, budget.inUse); assertFalse(slot.claimPlayback())
    }
    @Test fun prePrefixCancellationStillWaitsForNativeProducerExit() {
        val budget = PcmBudget(); var releases = 0
        val slot = StreamingPcmSlot(BoundedPcmStream(4), budget.reserve { false }!!) { releases++ }
        slot.discard(); assertEquals(1, budget.inUse)
        slot.producerDone(); slot.producerDone(); assertEquals(1, releases); assertEquals(0, budget.inUse)
    }
}
