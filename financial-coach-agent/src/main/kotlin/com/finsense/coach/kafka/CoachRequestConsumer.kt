package com.finsense.coach.kafka

import com.fasterxml.jackson.databind.ObjectMapper
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
        val event = objectMapper.readValue(payload, CoachRequestEvent::class.java)
        log.info(
            "Received coach request requestId={} userId={} trigger={}",
            event.requestId,
            event.userId,
            event.trigger
        )
        coachService.process(event)
    }
}
