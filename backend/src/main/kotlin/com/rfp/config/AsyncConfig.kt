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
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 10
        queueCapacity = 50
        setThreadNamePrefix("rfp-async-")
        initialize()
    }
}
