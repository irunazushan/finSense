package com.finsense.classifier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ClassificationResult(
    @Schema(description = "Correlation id for transaction")
    UUID transactionId,

    @Schema(description = "Predicted category")
    String category,

    @Schema(description = "Classification confidence in range 0.0-1.0")
    double confidence,

    @Schema(description = "Classification source")
    String source
) {
}
