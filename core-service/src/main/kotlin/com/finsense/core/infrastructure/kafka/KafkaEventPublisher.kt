package com.finsense.core.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(topic: String, key: String, event: Any) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, key, payload)
        log.info("Published event to topic={} key={}", topic, key)
    }
}
