package com.finsense.reasoning.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.config.KafkaProperties
import com.finsense.reasoning.config.TopicProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierResponseEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ClassifierResponseProducerTest {

    @Mock
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Test
    fun `serializes event and sends to configured topic`() {
        val producer = ClassifierResponseProducer(
            kafkaTemplate = kafkaTemplate,
            appProperties = AppProperties(
                kafka = KafkaProperties(
                    topics = TopicProperties(
                        llmClassifierRequests = "req",
                        llmClassifierResponses = "res"
                    )
                )
            ),
            objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        )
        val event = LlmClassifierResponseEvent(
            requestId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            category = "TRANSPORT",
            confidence = 0.98,
            processedAt = Instant.parse("2026-02-10T10:15:30Z")
        )

        producer.publish(event)

        val payloadCaptor = argumentCaptor<String>()
        verify(kafkaTemplate).send(eq("res"), eq(event.transactionId.toString()), payloadCaptor.capture())
        assertThat(payloadCaptor.firstValue).contains("\"category\":\"TRANSPORT\"")
        assertThat(payloadCaptor.firstValue).contains(event.requestId.toString())
    }
}
