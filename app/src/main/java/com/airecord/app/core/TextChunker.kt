package com.airecord.app.core

object TextChunker {
    /** Splits text into pieces of at most [maxChars], preferring line and sentence boundaries. */
    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)

        val pieces = trimmed.lines().flatMap { splitLong(it.trim(), maxChars) }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun splitLong(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val out = mutableListOf<String>()
        var rest = line
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = listOf(". ", "? ", "! ", "다. ", "요. ", ", ", " ")
                .map { sep -> window.lastIndexOf(sep).let { if (it > 0) it + sep.length else -1 } }
                .firstOrNull { it > maxChars / 2 } ?: maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
