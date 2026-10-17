package org.inkdock.reader

import org.json.JSONObject

/** A whole native queue is accepted or rejected; no text is silently truncated. */
data class OfflineSpeechSegment(val text: String, val block: Int, val requestId: String)
data class OfflineSpeechRequest(val queueId: String, val entries: List<OfflineSpeechSegment>) {
    companion object {
        const val MAX_JSON_UNITS = 12 * 1024 * 1024
        const val MAX_TEXT_UNITS = 8 * 1024 * 1024
        const val MAX_ENTRIES = 50000
        fun parse(json: String, maxSegmentUnits: Int = 3500): OfflineSpeechRequest {
            require(json.length <= MAX_JSON_UNITS) { "朗读队列过大：最多支持 12 Mi 字符的队列数据。" }
            val root = JSONObject(json)
            val rawId = root.get("queueId")
            require(rawId is String) { "朗读队列标识无效。" }
            val id = rawId
            require(id.isNotBlank() && id.length <= 100 && id.all { it.isLetterOrDigit() || it in "-_." }) { "朗读队列标识无效。" }
            val array = root.getJSONArray("entries")
            require(array.length() in 1..MAX_ENTRIES) { "朗读队列为空或超过 50000 个片段。" }
            var total = 0L
            val entries = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val rawText = entry.get("text")
                require(rawText is String) { "朗读片段必须是文字。" }
                val text = rawText.trim()
                val rawBlock = entry.get("block")
                require(rawBlock is Number && rawBlock.toDouble().isFinite() && rawBlock.toDouble() == rawBlock.toInt().toDouble() && rawBlock.toInt() >= -1) { "朗读段落编号无效。" }
                require(text.isNotEmpty() && text.length <= maxSegmentUnits) { "朗读片段为空或超过引擎长度上限，请重新选择较短文字。" }
                total += text.length
                require(total <= MAX_TEXT_UNITS) { "朗读文字过多：最多支持 8 Mi 字符，请从较后段落开始。" }
                OfflineSpeechSegment(text, rawBlock.toInt(), "$id-$index")
            }
            return OfflineSpeechRequest(id, entries)
        }
    }
}
