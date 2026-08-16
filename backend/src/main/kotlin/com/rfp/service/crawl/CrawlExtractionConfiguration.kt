package com.rfp.service.crawl

import com.rfp.service.LlmService
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class CrawlExtractionConfiguration {

    @Bean
    fun crawlClassifier(llmService: LlmService): CrawlClassifier = CrawlClassifier(llmService)

    @Bean
    fun urlCanonicalizer(): UrlCanonicalizer = UrlCanonicalizer()

    @Bean
    fun pageParser(urlCanonicalizer: UrlCanonicalizer): PageParser = PageParser(urlCanonicalizer)

    @Bean
    fun crawlPolicy(): CrawlPolicy = CrawlPolicy()

    /** Dedicated OkHttpClient for the crawl infrastructure — separate from the scraper client. */
    @Bean
    fun crawlOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false) // CrawlFetcher handles redirects manually
        .build()

    @Bean
    fun crawlRobotsPolicyService(
        crawlOkHttpClient: OkHttpClient,
        crawlPolicy: CrawlPolicy,
        urlCanonicalizer: UrlCanonicalizer,
    ): RobotsPolicyService = RobotsPolicyService(crawlOkHttpClient, crawlPolicy, urlCanonicalizer)

    @Bean
    fun crawlFetcher(
        crawlOkHttpClient: OkHttpClient,
        crawlPolicy: CrawlPolicy,
        urlCanonicalizer: UrlCanonicalizer,
        crawlRobotsPolicyService: RobotsPolicyService,
    ): CrawlFetcher = CrawlFetcher(crawlOkHttpClient, crawlPolicy, urlCanonicalizer, crawlRobotsPolicyService)

    @Bean
    fun sitemapParser(urlCanonicalizer: UrlCanonicalizer): SitemapParser = SitemapParser(urlCanonicalizer)
}
