package com.finsense.coach.logging

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class LlmLogRecord(
    val timestamp: Instant,
    val requestId: UUID,
    val userId: UUID,
    val model: String,
    val prompt: String,
    val response: Map<String, Any?>,
    val tokens: Int?,
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null
)

@Component
class LLMLogger(
    private val objectMapper: ObjectMapper
) {
    private val llmAuditLog = LoggerFactory.getLogger("com.finsense.coach.logging.llm-audit")

    fun log(record: LlmLogRecord) {
        llmAuditLog.info(objectMapper.writeValueAsString(record))
    }
}
