package org.inkdock.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechQueueTest {
    private fun json(vararg pairs: Pair<String, Int>): String = JSONObject().put("queueId", "offline-test")
        .put("entries", JSONArray().apply { pairs.forEach { put(JSONObject().put("text", it.first).put("block", it.second)) } }).toString()
    @Test fun preservesWholeQueueAndStableIds() {
        val queue = OfflineSpeechRequest.parse(json("第一段" to 8, "第二段" to 9))
        assertEquals("offline-test", queue.queueId)
        assertEquals(listOf(8, 9), queue.entries.map { it.block })
        assertEquals(listOf("offline-test-0", "offline-test-1"), queue.entries.map { it.requestId })
        assertEquals("第二段", queue.entries[1].text)
    }
    @Test fun acceptsSelectionSentinel() { assertEquals(-1, OfflineSpeechRequest.parse(json("选择内容" to -1)).entries.single().block) }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyQueue() { OfflineSpeechRequest.parse(json()) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOverlongSegmentRatherThanTruncating() { OfflineSpeechRequest.parse(json("一".repeat(3501) to 0)) }
    @Test(expected = IllegalArgumentException::class) fun honorsEngineSpecificLimit() { OfflineSpeechRequest.parse(json("一二三四" to 0), 3) }
    @Test(expected = IllegalArgumentException::class) fun rejectsBlankText() { OfflineSpeechRequest.parse(json(" \n\t" to 0)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidBlock() { OfflineSpeechRequest.parse(json("正文" to -2)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsFractionalBlock() { OfflineSpeechRequest.parse("""{"queueId":"q","entries":[{"text":"正文","block":0.5}]}""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidIdentifier() { OfflineSpeechRequest.parse("""{"queueId":"bad id","entries":[{"text":"正文","block":0}]}""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonTextSegment() { OfflineSpeechRequest.parse("""{"queueId":"q","entries":[{"text":123,"block":0}]}""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedJsonBeforeParsing() { OfflineSpeechRequest.parse(" ".repeat(OfflineSpeechRequest.MAX_JSON_UNITS + 1)) }
}
