package com.finsense.reasoning.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class AppPropertiesBindingTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "app.kafka.topics.llm-classifier-requests=req-topic",
            "app.kafka.topics.llm-classifier-responses=res-topic",
            "app.llm.timeout-seconds=25",
            "app.llm.retry.max-attempts=4",
            "app.llm.retry.backoff-delay-ms=1500",
            "app.llm.allowed-categories[0]=FOOD_AND_DRINKS",
            "app.llm.allowed-categories[1]=OTHER",
            "app.logging.llm-logs-dir=/tmp/reasoning"
        )

    @Test
    fun `binds app properties`() {
        contextRunner.run { context ->
            val props = context.getBean(AppProperties::class.java)
            assertThat(props.kafka.topics.llmClassifierRequests).isEqualTo("req-topic")
            assertThat(props.kafka.topics.llmClassifierResponses).isEqualTo("res-topic")
            assertThat(props.llm.timeoutSeconds).isEqualTo(25)
            assertThat(props.llm.retry.maxAttempts).isEqualTo(4)
            assertThat(props.llm.retry.backoffDelayMs).isEqualTo(1500)
            assertThat(props.llm.normalizedAllowedCategories()).containsExactlyInAnyOrder("FOOD_AND_DRINKS", "OTHER")
            assertThat(props.logging.llmLogsDir).isEqualTo("/tmp/reasoning")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties::class)
    class TestConfig
}
