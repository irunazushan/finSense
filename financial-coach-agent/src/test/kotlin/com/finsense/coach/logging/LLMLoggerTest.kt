package com.finsense.coach.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class LLMLoggerTest {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
    private val logger = LLMLogger(objectMapper)

    @Test
    fun `writes valid json audit record`() {
        val requestId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val auditLogger = LoggerFactory.getLogger("com.finsense.coach.logging.llm-audit") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        auditLogger.addAppender(listAppender)

        logger.log(
            LlmLogRecord(
                timestamp = Instant.now(),
                requestId = requestId,
                userId = userId,
                model = "deepseek-chat",
                prompt = "prompt",
                response = mapOf("message" to "ok"),
                tokens = 15,
                latencyMs = 45,
                success = true
            )
        )

        val message = listAppender.list.single().formattedMessage
        val json = objectMapper.readTree(message)
        assertThat(json["requestId"].asText()).isEqualTo(requestId.toString())
        assertThat(json["userId"].asText()).isEqualTo(userId.toString())
        assertThat(json["model"].asText()).isEqualTo("deepseek-chat")
        assertThat(json["success"].asBoolean()).isTrue()
    }
}
