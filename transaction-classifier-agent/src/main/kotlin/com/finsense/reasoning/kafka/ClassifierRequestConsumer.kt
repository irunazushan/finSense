package com.finsense.reasoning.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.service.ClassificationService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ClassifierRequestConsumer(
    private val objectMapper: ObjectMapper,
    private val classificationService: ClassificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${app.kafka.topics.llm-classifier-requests}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(payload: String) {
        val event = parseRequest(payload) ?: return
        log.info(
            "Received LLM classifier request transactionId={} requestId={}",
            event.transactionId,
            event.requestId
        )
        classificationService.process(event)
    }

    private fun parseRequest(payload: String): LlmClassifierRequestEvent? {
        return try {
            objectMapper.readValue(payload, LlmClassifierRequestEvent::class.java)
        } catch (ex: MismatchedInputException) {
            runCatching {
                val unwrapped = objectMapper.readValue(payload, String::class.java)
                objectMapper.readValue(unwrapped, LlmClassifierRequestEvent::class.java)
            }.getOrElse {
                log.error("Invalid llm-classifier-request payload skipped: {}", payload.take(300), ex)
                null
            }
        } catch (ex: Exception) {
            log.error("Failed to parse llm-classifier-request payload skipped: {}", payload.take(300), ex)
            null
        }
    }
}
