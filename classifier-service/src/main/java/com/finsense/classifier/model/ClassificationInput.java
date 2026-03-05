package com.finsense.classifier.model;

import java.math.BigDecimal;
import java.util.UUID;

public record ClassificationInput(
    UUID transactionId,
    BigDecimal amount,
    String description,
    String merchantName,
    String mccCode
) {
}
