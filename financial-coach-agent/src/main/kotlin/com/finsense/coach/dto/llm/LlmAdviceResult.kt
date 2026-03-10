package com.finsense.coach.dto.llm

data class LlmAdviceResult(
    val summary: String,
    val advice: String,
    val rawText: String,
    val tokens: Int?,
    val latencyMs: Long
)