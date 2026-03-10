package com.finsense.coach.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.finsense.coach.dto.kafka.CoachRequestEvent
import com.finsense.coach.service.CoachService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class CoachRequestConsumer(
    private val objectMapper: ObjectMapper,
    private val coachService: CoachService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${app.kafka.topics.coach-requests}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(payload: String) {
        val event = parseCoachRequest(payload) ?: return
        log.info(
            "Received coach request requestId={} userId={} trigger={}",
            event.requestId,
            event.userId,
            event.trigger
        )
        coachService.process(event)
    }

    private fun parseCoachRequest(payload: String): CoachRequestEvent? {
        return try {
            objectMapper.readValue(payload, CoachRequestEvent::class.java)
        } catch (ex: MismatchedInputException) {
            // Some producers can accidentally send a JSON string containing JSON.
            // Try one extra decode step before dropping the message.
            runCatching {
                val unwrapped = objectMapper.readValue(payload, String::class.java)
                objectMapper.readValue(unwrapped, CoachRequestEvent::class.java)
            }.getOrElse {
                log.error(
                    "Invalid coach-request payload skipped: {}",
                    payload.take(300),
                    ex
                )
                null
            }
        } catch (ex: Exception) {
            log.error(
                "Failed to parse coach-request payload, message skipped: {}",
                payload.take(300),
                ex
            )
            null
        }
    }
}
