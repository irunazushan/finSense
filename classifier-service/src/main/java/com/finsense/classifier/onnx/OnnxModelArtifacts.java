package com.finsense.classifier.onnx;

import com.finsense.classifier.model.TransactionCategory;
import java.nio.file.Path;
import java.util.List;

public record OnnxModelArtifacts(
    Path modelPath,
    Path labelsPath,
    Path metadataPath,
    List<TransactionCategory> labels,
    String missingMccToken,
    double amountClipMax,
    String amountTransform,
    String modelType,
    int targetOpset,
    String datasetId
) {
}
