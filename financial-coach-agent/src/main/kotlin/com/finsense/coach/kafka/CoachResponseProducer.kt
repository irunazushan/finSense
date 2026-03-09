package com.finsense.coach.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.config.AppProperties
import com.finsense.coach.dto.kafka.CoachResponseEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class CoachResponseProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: CoachResponseEvent) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(appProperties.kafka.topics.coachResponses, event.userId.toString(), payload)
        log.info(
            "Published coach response requestId={} userId={} status={}",
            event.requestId,
            event.userId,
            event.status
        )
    }
}
