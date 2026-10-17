package org.inkdock.reader

/** Native buffer units; external paragraph/request IDs remain unchanged. */
internal object SpeechSentenceChunks {
    const val MAX_UNITS = 120
    fun split(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + MAX_UNITS).coerceAtMost(text.length)
            if (end < text.length) {
                if (Character.isLowSurrogate(text[end]) && end > start) end--
                val strong = (start until end).lastOrNull { text[it] in "。！？!?；;\n" ||
                    (text[it] == '.' && (it + 1 == text.length || text[it + 1].isWhitespace())) }
                val soft = (start until end).lastOrNull { text[it] in "，,、：:" || text[it].isWhitespace() }
                val boundary = strong ?: soft
                if (boundary != null && boundary >= start + 24) end = boundary + 1
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }
}
