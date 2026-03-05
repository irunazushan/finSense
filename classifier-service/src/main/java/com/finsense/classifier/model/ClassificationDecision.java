package com.finsense.classifier.model;

import java.util.UUID;

public record ClassificationDecision(
    UUID transactionId,
    TransactionCategory category,
    double confidence,
    String source
) {
}
