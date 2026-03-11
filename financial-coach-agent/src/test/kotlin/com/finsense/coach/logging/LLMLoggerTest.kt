package com.finsense.coach.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.finsense.coach.config.AppProperties
import com.finsense.coach.config.LoggingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class LLMLoggerTest {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
    private val logger = LLMLogger(
        objectMapper = objectMapper,
        appProperties = AppProperties(logging = LoggingProperties("./build/test-llm-logs"))
    )

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
                usedModel = "deepseek-chat",
                configuredModel = null,
                systemTemplateId = "coach-system-v1",
                userTemplateId = "coach-user-v1",
                renderedUserPrompt = "requestId: 1",
                rawResponse = mapOf("message" to "ok"),
                totalTokens = 15,
                latencyMs = 45,
                success = true
            )
        )

        val message = listAppender.list.single().formattedMessage
        val json = objectMapper.readTree(message)
        assertThat(json["requestId"].asText()).isEqualTo(requestId.toString())
        assertThat(json["userId"].asText()).isEqualTo(userId.toString())
        assertThat(json["usedModel"].asText()).isEqualTo("deepseek-chat")
        assertThat(json["systemTemplateId"].asText()).isEqualTo("coach-system-v1")
        assertThat(json["userTemplateId"].asText()).isEqualTo("coach-user-v1")
        assertThat(json["renderedUserPrompt"].asText()).isEqualTo("requestId: 1")
        assertThat(json["rawResponse"]["message"].asText()).isEqualTo("ok")
        assertThat(json["totalTokens"].asInt()).isEqualTo(15)
        assertThat(json["success"].asBoolean()).isTrue()
        assertThat(json.has("systemPrompt")).isFalse()
        assertThat(json.has("userPrompt")).isFalse()
        assertThat(json.has("response")).isFalse()
    }

    @Test
    fun `creates missing log directory before write`() {
        val logsDir = Files.createTempDirectory("llm-logger-test").resolve("nested").toString()
        val localLogger = LLMLogger(
            objectMapper = objectMapper,
            appProperties = AppProperties(logging = LoggingProperties(logsDir))
        )

        localLogger.log(
            LlmLogRecord(
                timestamp = Instant.now(),
                requestId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                usedModel = "deepseek-chat",
                configuredModel = null,
                systemTemplateId = "coach-system-v1",
                userTemplateId = "coach-user-v1",
                renderedUserPrompt = "requestId: 1",
                rawResponse = mapOf("message" to "ok"),
                totalTokens = 1,
                latencyMs = 1,
                success = true
            )
        )

        assertThat(Files.exists(java.nio.file.Paths.get(logsDir))).isTrue()
    }
}
