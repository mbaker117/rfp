package com.rfp.service.crawl

import com.rfp.service.LlmService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CrawlExtractionConfiguration {
    @Bean
    fun crawlClassifier(llmService: LlmService): CrawlClassifier = CrawlClassifier(llmService)

    @Bean
    fun productPageExtractor(
        llmService: LlmService,
        crawlClassifier: CrawlClassifier,
    ): ProductPageExtractor = ProductPageExtractor(
        llmService = llmService,
        classifier = crawlClassifier,
    )
}
