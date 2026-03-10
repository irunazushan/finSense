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
            assertThat(props.llm.timeoutSeconds).isEqualTo(20)
            assertThat(props.analytics.topMerchantsLimit).isEqualTo(7)
            assertThat(props.kafka.topics.coachResponses).isEqualTo("coach-responses")
            assertThat(props.logging.llmLogsDir).isEqualTo("/tmp/coach")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties::class)
    class TestConfig
}
