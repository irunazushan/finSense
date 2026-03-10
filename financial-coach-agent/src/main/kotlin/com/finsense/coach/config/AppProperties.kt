package com.finsense.coach.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val llm: LlmProperties = LlmProperties(),
    val analytics: AnalyticsProperties = AnalyticsProperties(),
    val kafka: KafkaProperties = KafkaProperties(),
    val logging: LoggingProperties = LoggingProperties()
)

data class LlmProperties(
    val timeoutSeconds: Long = 15
)

data class AnalyticsProperties(
    val topMerchantsLimit: Int = 5
)

data class KafkaProperties(
    val topics: TopicProperties = TopicProperties()
)

data class TopicProperties(
    val coachRequests: String = "coach-requests",
    val coachResponses: String = "coach-responses"
)

data class LoggingProperties(
    val llmLogsDir: String = "./logs/coach-llm"
)
