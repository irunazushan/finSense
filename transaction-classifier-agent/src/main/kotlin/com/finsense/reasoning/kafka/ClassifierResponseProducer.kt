package com.finsense.reasoning.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierResponseEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class ClassifierResponseProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: LlmClassifierResponseEvent) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(
            appProperties.kafka.topics.llmClassifierResponses,
            event.transactionId.toString(),
            payload
        )
        log.info(
            "Published LLM classifier response transactionId={} requestId={} category={} confidence={}",
            event.transactionId,
            event.requestId,
            event.category,
            event.confidence
        )
    }
}
