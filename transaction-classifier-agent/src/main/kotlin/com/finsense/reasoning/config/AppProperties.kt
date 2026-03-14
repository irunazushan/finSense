package com.finsense.reasoning.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val kafka: KafkaProperties = KafkaProperties(),
    val llm: LlmProperties = LlmProperties(),
    val logging: LoggingProperties = LoggingProperties()
)

data class KafkaProperties(
    val topics: TopicProperties = TopicProperties()
)

data class TopicProperties(
    val llmClassifierRequests: String = "llm-classifier-requests",
    val llmClassifierResponses: String = "llm-classifier-responses"
)

data class LlmProperties(
    val timeoutSeconds: Long = 60,
    val retry: RetryProperties = RetryProperties(),
    val allowedCategories: List<String> = listOf(
        "FOOD_AND_DRINKS",
        "TRANSPORT",
        "SHOPPING",
        "ENTERTAINMENT",
        "HEALTH",
        "OTHER",
        "UNDEFINED"
    )
) {
    fun normalizedAllowedCategories(): Set<String> = allowedCategories
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .toSet()
}

data class RetryProperties(
    val maxAttempts: Int = 3,
    val backoffDelayMs: Long = 1000
)

data class LoggingProperties(
    val llmLogsDir: String = "./logs/llm-classifier"
)
