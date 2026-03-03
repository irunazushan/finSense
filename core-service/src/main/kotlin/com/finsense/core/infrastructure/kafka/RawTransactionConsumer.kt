package com.finsense.core.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.core.dto.kafka.RawTransactionEvent
import com.finsense.core.service.TransactionService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class RawTransactionConsumer(
    private val objectMapper: ObjectMapper,
    private val transactionService: TransactionService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${app.kafka.topics.raw-transactions}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(payload: String) {
        val event = objectMapper.readValue(payload, RawTransactionEvent::class.java)
        log.info("Received raw transaction event transactionId={} userId={}", event.transactionId, event.userId)
        transactionService.handleRawTransaction(event)
    }
}
