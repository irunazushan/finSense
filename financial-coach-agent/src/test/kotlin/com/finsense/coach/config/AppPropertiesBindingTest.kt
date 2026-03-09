package com.finsense.coach.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class AppPropertiesBindingTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "app.llm.provider=deepseek",
            "app.llm.api-url=https://api.deepseek.com/v1/chat/completions",
            "app.llm.api-key=abc123",
            "app.llm.model=deepseek-chat",
            "app.llm.max-tokens=512",
            "app.llm.temperature=0.2",
            "app.llm.timeout-seconds=20",
            "app.analytics.top-merchants-limit=7",
            "app.kafka.topics.coach-requests=coach-requests",
            "app.kafka.topics.coach-responses=coach-responses",
            "app.logging.llm-logs-dir=/tmp/coach"
        )

    @Test
    fun `binds app properties`() {
        contextRunner.run { context ->
            val props = context.getBean(AppProperties::class.java)
            assertThat(props.llm.provider).isEqualTo("deepseek")
            assertThat(props.llm.apiKey).isEqualTo("abc123")
            assertThat(props.llm.maxTokens).isEqualTo(512)
            assertThat(props.analytics.topMerchantsLimit).isEqualTo(7)
            assertThat(props.kafka.topics.coachResponses).isEqualTo("coach-responses")
            assertThat(props.logging.llmLogsDir).isEqualTo("/tmp/coach")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties::class)
    class TestConfig
}
