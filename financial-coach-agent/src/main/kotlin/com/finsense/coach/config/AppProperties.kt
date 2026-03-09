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
    val provider: String = "deepseek",
    val apiUrl: String = "https://api.deepseek.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val maxTokens: Int = 300,
    val temperature: Double = 0.3,
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
    val llmLogsDir: String = "/var/log/finsense/coach-llm"
)
