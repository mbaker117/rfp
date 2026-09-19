package com.rfp.service

/**
 * Splits extracted catalog text into chunks small enough for one LLM extraction call each.
 * Pages (form-feed separated, see [DocumentParsingService]) are kept whole and packed together;
 * a page larger than [maxChars] is split on line boundaries, and a single over-long line is hard split.
 */
object CatalogChunker {
    private const val PAGE_BREAK = '\u000C'

    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val units = text.split(PAGE_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { page -> if (page.length <= maxChars) listOf(page) else splitLines(page, maxChars) }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (unit in units) {
            if (current.isNotEmpty() && current.length + 1 + unit.length > maxChars) {
                chunks.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(unit)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private fun splitLines(page: String, maxChars: Int): List<String> =
        page.lines()
            .filter { it.isNotBlank() }
            .flatMap { line -> if (line.length <= maxChars) listOf(line) else line.chunked(maxChars) }
}
