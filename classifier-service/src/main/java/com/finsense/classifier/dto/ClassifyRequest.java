package com.finsense.classifier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ClassifyRequest(
    @NotNull
    @Schema(description = "Correlation id for transaction", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID transactionId,

    @NotNull
    @Schema(description = "Transaction amount", requiredMode = Schema.RequiredMode.REQUIRED)
    BigDecimal amount,

    @Schema(description = "Transaction description")
    String description,

    @Schema(description = "Merchant name")
    String merchantName,

    @Schema(description = "Merchant category code")
    String mccCode
) {
}
