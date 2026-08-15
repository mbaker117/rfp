package com.rfp.service.crawl

import com.rfp.service.LlmService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CrawlExtractionConfiguration {
    @Bean
    fun crawlClassifier(llmService: LlmService): CrawlClassifier = CrawlClassifier(llmService)

}
