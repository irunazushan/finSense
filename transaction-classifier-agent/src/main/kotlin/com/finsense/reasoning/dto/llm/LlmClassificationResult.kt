package com.finsense.reasoning.dto.llm

data class LlmClassificationResult(
    val category: String,
    val confidence: Double,
    val reasoning: String,
    val rawText: String,
    val usedModel: String,
    val totalTokens: Int?,
    val latencyMs: Long
)
