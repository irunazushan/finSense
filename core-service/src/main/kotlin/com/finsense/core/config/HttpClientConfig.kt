package com.finsense.core.config

import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Configuration
class HttpClientConfig {
    @Bean
    fun restTemplate(appProperties: AppProperties): RestTemplate {
        val timeout = Duration.ofMillis(appProperties.classifier.timeoutMs)
        return RestTemplateBuilder()
            .setConnectTimeout(timeout)
            .setReadTimeout(timeout)
            .build()
    }
}
