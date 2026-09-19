package com.rfp.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogChunkerTest {

    @Test
    fun `blank text yields no chunks`() {
        assertThat(CatalogChunker.chunk("  \n\u000C \n", 100)).isEmpty()
    }

    @Test
    fun `small text is a single chunk`() {
        assertThat(CatalogChunker.chunk("Fluke 179 multimeter", 100)).containsExactly("Fluke 179 multimeter")
    }

    @Test
    fun `pages are packed together up to the limit and never split when they fit`() {
        val page = "p".repeat(40)
        val text = listOf(page, page, page, page).joinToString("\u000C")

        val chunks = CatalogChunker.chunk(text, 100)

        assertThat(chunks).hasSize(2)
        assertThat(chunks).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(100) }
        assertThat(chunks.joinToString("").replace("\n", "")).isEqualTo(page.repeat(4))
    }

    @Test
    fun `an oversized page is split on line boundaries`() {
        val lines = (1..30).joinToString("\n") { "line-$it ".padEnd(20, 'x') }

        val chunks = CatalogChunker.chunk(lines, 100)

        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(100) }
        assertThat(chunks.first()).startsWith("line-1 ")
        assertThat(chunks.joinToString("\n").lines().map { it.trim() }.filter { it.isNotEmpty() })
            .hasSize(30)
    }

    @Test
    fun `a single line longer than the limit is hard split`() {
        val chunks = CatalogChunker.chunk("a".repeat(250), 100)

        assertThat(chunks.map { it.length }).containsExactly(100, 100, 50)
    }
}
