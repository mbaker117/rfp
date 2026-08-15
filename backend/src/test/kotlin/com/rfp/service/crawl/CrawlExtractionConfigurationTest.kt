package com.rfp.service.crawl

import com.rfp.service.LlmClient
import com.rfp.service.LlmService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.net.URI

class CrawlExtractionConfigurationTest {
    @Test
    fun `production beans inject bounded classifier into extractor`() {
        val responses = ArrayDeque(listOf(
            "not-json",
            """{"schemaVersion":"1.0","products":[{"identityHint":"SAFE-1","name":"Safe Meter",
                "mpn":"SAFE-1","className":null,"attributes":{"description":"safe"},"price":null,
                "currency":null,"priceSourceUrl":null,"sourceUrl":"https://example.com/measurement",
                "confidence":80}]}""".trimIndent(),
        ))
        val client = object : LlmClient {
            override fun call(systemPrompt: String, userMessage: String): String = responses.removeFirst()
        }
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("llmService", LlmService(client))
            context.register(ProductPageExtractor::class.java, CrawlExtractionConfiguration::class.java)
            context.refresh()

            val page = ParsedPage(
                title = null,
                canonicalUrl = URI("https://example.com/measurement"),
                visibleText = "precision instrument SAFE-1",
                links = emptyList(),
                jsonLdProducts = emptyList(),
                embeddedJson = emptyList(),
                pagination = emptyList(),
                documents = emptyList(),
                signals = PageSignals(false, false, 0),
            )
            val observations = context.getBean(ProductPageExtractor::class.java).extract(page, emptyList())

            assertThat(context.getBean(CrawlClassifier::class.java)).isNotNull
            assertThat(observations.single().mpn).isEqualTo("SAFE-1")
        }
    }
}
