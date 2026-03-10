package com.finsense.coach.dto.llm

data class LlmAdviceResult(
    val summary: String,
    val advice: String,
    val rawText: String,
    val usedModel: String,
    val totalTokens: Int?,
    val latencyMs: Long
)
