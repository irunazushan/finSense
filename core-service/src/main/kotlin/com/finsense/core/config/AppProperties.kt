package com.finsense.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val classifier: ClassifierProperties = ClassifierProperties(),
    val reasoning: ReasoningProperties = ReasoningProperties(),
    val scheduler: SchedulerProperties = SchedulerProperties(),
    val kafka: KafkaProperties = KafkaProperties()
)

data class ClassifierProperties(
    val url: String = "http://classifier:8081",
    val confidenceThreshold: Double = 0.9,
    val timeoutMs: Long = 3000
)

data class ReasoningProperties(
    val historySize: Int = 20
)

data class SchedulerProperties(
    val coachCron: String = "0 0 2 * * ?"
)

data class KafkaProperties(
    val topics: TopicsProperties = TopicsProperties()
)

data class TopicsProperties(
    val rawTransactions: String = "raw-transactions",
    val llmClassifierRequests: String = "llm-classifier-requests",
    val llmClassifierResponses: String = "llm-classifier-responses",
    val coachRequests: String = "coach-requests"
)
