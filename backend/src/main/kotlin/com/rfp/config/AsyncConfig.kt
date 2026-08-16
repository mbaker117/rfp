package com.rfp.config

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class AsyncConfig {
    /** General-purpose async executor for RFP matching and scraping jobs. */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 10
        queueCapacity = 50
        setThreadNamePrefix("rfp-async-")
        initialize()
    }

    /**
     * Dedicated thread pool for adaptive crawl batches.
     * Kept separate from [taskExecutor] so long-running crawl batches
     * cannot starve RFP matching or catalog ingest tasks.
     */
    @Bean(name = ["crawlBatchExecutor"])
    fun crawlBatchExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 20
        setThreadNamePrefix("crawl-batch-")
        initialize()
    }
}
