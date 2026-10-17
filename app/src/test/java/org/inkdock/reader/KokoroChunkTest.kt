package org.inkdock.reader
import org.junit.Test
import org.junit.Assert.*
class KokoroChunkTest {
 @Test fun boundedChunksPreserveChineseAndSurrogates(){val t="风轻轻吹过。"+"你好😀".repeat(200);val p=KokoroOfflineSpeechEngine.split(t);assertEquals(t,p.joinToString(""));assertTrue(p.all{it.length<=121});assertTrue(p.none{it.last().isHighSurrogate()||it.first().isLowSurrogate()})}
 @Test fun blankProducesNoChunks(){assertTrue(KokoroOfflineSpeechEngine.split(" \n ").isEmpty())}
}
