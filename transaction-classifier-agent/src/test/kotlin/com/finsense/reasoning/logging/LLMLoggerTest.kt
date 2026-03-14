package com.finsense.reasoning.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.finsense.reasoning.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class LLMLoggerTest {

    @Test
    fun `emits json audit record`() {
        val auditLogger = LoggerFactory.getLogger("com.finsense.reasoning.logging.llm-audit") as Logger
        val listAppender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        listAppender.start()
        auditLogger.addAppender(listAppender)
        auditLogger.level = Level.INFO

        try {
            val logger = LLMLogger(
                objectMapper = ObjectMapper().registerModule(JavaTimeModule()),
                appProperties = AppProperties()
            )
            val requestId = UUID.randomUUID()
            val transactionId = UUID.randomUUID()

            logger.log(
                LlmLogRecord(
                    timestamp = Instant.parse("2026-02-10T10:15:30Z"),
                    requestId = requestId,
                    transactionId = transactionId,
                    userId = UUID.randomUUID(),
                    usedModel = "deepseek-chat",
                    systemTemplateId = "classifier-system-v1",
                    userTemplateId = "classifier-user-v1",
                    renderedUserPrompt = "prompt",
                    rawResponse = mapOf("raw" to true),
                    totalTokens = 42,
                    latencyMs = 1200,
                    success = true
                )
            )

            assertThat(listAppender.list).isNotEmpty
            assertThat(listAppender.list.last().formattedMessage).contains(requestId.toString())
            assertThat(listAppender.list.last().formattedMessage).contains(transactionId.toString())
            assertThat(listAppender.list.last().formattedMessage).contains("\"totalTokens\":42")
        } finally {
            auditLogger.detachAppender(listAppender)
        }
    }
}
