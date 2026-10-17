package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class SpeechSentenceChunksTest {
    @Test fun splitsLongChineseAtSentenceBoundariesWithoutLoss() {
        val text = ("这是人工合成的缓冲测试文本，不包含用户正文。".repeat(60))
        val parts = SpeechSentenceChunks.split(text)
        assertTrue(parts.size > 1); assertTrue(parts.all { it.length <= 120 })
        assertEquals(text, parts.joinToString(""))
    }
    @Test fun hardSplitDoesNotBreakSurrogatePairsOrLoseTail() {
        val text = "文".repeat(119) + "😀".repeat(180) + "结束"
        val parts = SpeechSentenceChunks.split(text)
        assertEquals(text, parts.joinToString("")); assertTrue(parts.all { it.length <= 120 })
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
    @Test fun shortAndWhitespaceOnlyInputsAreSafe() {
        assertEquals(listOf("Synthetic text."), SpeechSentenceChunks.split(" Synthetic text. "))
        assertEquals(emptyList<String>(), SpeechSentenceChunks.split(" \n\t "))
    }
    @Test fun manySmallSentencesArePackedWithinBound() {
        val text = "测试。".repeat(1000)
        val parts = SpeechSentenceChunks.split(text)
        assertEquals(text, parts.joinToString("")); assertTrue(parts.size <= 30)
    }
}
