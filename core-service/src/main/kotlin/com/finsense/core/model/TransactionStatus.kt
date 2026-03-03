package com.finsense.core.model

enum class TransactionStatus {
    NEW,
    ML_CLASSIFYING,
    LLM_CLASSIFYING,
    CLASSIFIED,
    RETRYING,
    FAILED
}
