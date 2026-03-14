package com.finsense.reasoning.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

data class LlmLogRecord(
    val timestamp: Instant,
    val requestId: UUID,
    val transactionId: UUID,
    val userId: UUID,
    val usedModel: String,
    val configuredModel: String? = null,
    val systemTemplateId: String,
    val userTemplateId: String,
    val renderedUserPrompt: String,
    val rawResponse: Any?,
    val totalTokens: Int?,
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null
)

@Component
class LLMLogger(
    private val objectMapper: ObjectMapper,
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val llmAuditLog = LoggerFactory.getLogger("com.finsense.reasoning.logging.llm-audit")

    fun log(record: LlmLogRecord) {
        ensureLogDirectory()
        runCatching {
            llmAuditLog.info(objectMapper.writeValueAsString(record))
        }.onFailure { ex ->
            log.warn(
                "Failed to write LLM audit record requestId={} transactionId={}: {}",
                record.requestId,
                record.transactionId,
                ex.message
            )
        }
    }

    private fun ensureLogDirectory() {
        val path = Paths.get(appProperties.logging.llmLogsDir)
        runCatching { Files.createDirectories(path) }
            .onFailure { ex ->
                log.warn("Failed to create LLM log directory {}: {}", path.toAbsolutePath(), ex.message)
            }
    }
}
