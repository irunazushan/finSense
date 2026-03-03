package com.finsense.core.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.core.dto.kafka.LlmClassifierResponseEvent
import com.finsense.core.service.TransactionService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class LlmClassifierResponseConsumer(
    private val objectMapper: ObjectMapper,
    private val transactionService: TransactionService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${app.kafka.topics.llm-classifier-responses}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(payload: String) {
        val event = objectMapper.readValue(payload, LlmClassifierResponseEvent::class.java)
        log.info("Received LLM classifier response transactionId={} requestId={}", event.transactionId, event.requestId)
        transactionService.handleLlmClassifierResponse(event)
    }
}
