package com.finsense.classifier.onnx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsense.classifier.config.ClassificationProperties;
import com.finsense.classifier.model.TransactionCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.classification", name = "strategy", havingValue = "ml")
public class OnnxModelLoader {

    static final String MODEL_FILENAME = "transaction-classifier.onnx";
    static final String LABELS_FILENAME = "labels.json";
    static final String METADATA_FILENAME = "metadata.json";
    static final List<String> EXPECTED_INPUTS = List.of("text", "mccCode", "amount");
    static final String EXPECTED_MISSING_MCC_TOKEN = "__MISSING_MCC__";
    static final String EXPECTED_AMOUNT_TRANSFORM = "signed_log1p";
    static final double EXPECTED_CLIP_MAX = 50000.0;

    private static final Logger log = LoggerFactory.getLogger(OnnxModelLoader.class);

    private final ObjectMapper objectMapper;
    private final ClassificationProperties properties;
    private final OnnxModelArtifacts artifacts;

    public OnnxModelLoader(
        ObjectMapper objectMapper,
        ClassificationProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.artifacts = load();
    }

    public OnnxModelArtifacts getArtifacts() {
        return artifacts;
    }

    OnnxModelArtifacts load() {
        Path modelDir = Path.of(properties.getModel().getDir()).toAbsolutePath().normalize();
        validateFile(modelDir, true);

        Path modelPath = modelDir.resolve(MODEL_FILENAME);
        Path labelsPath = modelDir.resolve(LABELS_FILENAME);
        Path metadataPath = modelDir.resolve(METADATA_FILENAME);
        validateFile(modelPath, false);
        validateFile(labelsPath, false);
        validateFile(metadataPath, false);

        JsonNode labelsDocument = readJson(labelsPath, "labels");
        List<TransactionCategory> labels = parseLabels(labelsDocument, labelsPath);
        JsonNode metadataDocument = readJson(metadataPath, "metadata");

        validateExpectedStringArray(metadataDocument.path("inputs"), EXPECTED_INPUTS, metadataPath, "inputs");
        String missingMccToken = validateRequiredText(
            metadataDocument.path("missing_mcc_token"),
            metadataPath,
            "missing_mcc_token"
        );
        if (!EXPECTED_MISSING_MCC_TOKEN.equals(missingMccToken)) {
            throw new IllegalStateException(
                "Invalid metadata in " + metadataPath + ": missing_mcc_token must be " + EXPECTED_MISSING_MCC_TOKEN
            );
        }

        JsonNode amountPreprocessing = metadataDocument.path("amount_preprocessing");
        String amountTransform = validateRequiredText(
            amountPreprocessing.path("transform"),
            metadataPath,
            "amount_preprocessing.transform"
        );
        if (!EXPECTED_AMOUNT_TRANSFORM.equals(amountTransform)) {
            throw new IllegalStateException(
                "Invalid metadata in " + metadataPath + ": amount_preprocessing.transform must be "
                    + EXPECTED_AMOUNT_TRANSFORM
            );
        }

        JsonNode clipMaxNode = amountPreprocessing.path("clip_max");
        if (!clipMaxNode.isNumber() || Double.compare(clipMaxNode.doubleValue(), EXPECTED_CLIP_MAX) != 0) {
            throw new IllegalStateException(
                "Invalid metadata in " + metadataPath + ": amount_preprocessing.clip_max must be "
                    + EXPECTED_CLIP_MAX
            );
        }

        String modelType = optionalText(metadataDocument.path("model_type"));
        int targetOpset = metadataDocument.path("target_opset").asInt(0);
        String datasetId = optionalText(metadataDocument.path("training_data").path("dataset_id"));

        log.info(
            "Loaded ML model strategy=ml modelDir={} modelType={} datasetId={} labelCount={}",
            modelDir,
            modelType == null ? "unknown" : modelType,
            datasetId == null ? "unknown" : datasetId,
            labels.size()
        );

        return new OnnxModelArtifacts(
            modelPath,
            labelsPath,
            metadataPath,
            List.copyOf(labels),
            missingMccToken,
            clipMaxNode.doubleValue(),
            amountTransform,
            modelType == null ? "unknown" : modelType,
            targetOpset,
            datasetId == null ? "unknown" : datasetId
        );
    }

    private List<TransactionCategory> parseLabels(JsonNode document, Path labelsPath) {
        JsonNode labelsNode = document.path("labels");
        if (!labelsNode.isArray() || labelsNode.isEmpty()) {
            throw new IllegalStateException("Invalid labels artifact: " + labelsPath + " must contain a non-empty labels array");
        }

        List<TransactionCategory> labels = new ArrayList<>();
        Set<TransactionCategory> seen = EnumSet.noneOf(TransactionCategory.class);
        for (JsonNode labelNode : labelsNode) {
            String rawLabel = validateRequiredText(labelNode, labelsPath, "labels[]");
            try {
                TransactionCategory label = TransactionCategory.valueOf(rawLabel);
                if (!seen.add(label)) {
                    throw new IllegalStateException("Invalid labels artifact: duplicate label '" + rawLabel + "'");
                }
                labels.add(label);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                    "Invalid labels artifact: unsupported label '" + rawLabel + "' in " + labelsPath,
                    ex
                );
            }
        }

        EnumSet<TransactionCategory> expected = EnumSet.allOf(TransactionCategory.class);
        EnumSet<TransactionCategory> actual = EnumSet.copyOf(labels);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                "Invalid labels artifact: labels in " + labelsPath + " must exactly match " + expected
            );
        }
        return labels;
    }

    private JsonNode readJson(Path path, String artifactType) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + artifactType + " artifact: " + path, ex);
        }
    }

    private void validateFile(Path path, boolean directory) {
        if (directory) {
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException("Model directory does not exist: " + path);
            }
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("Model directory is not readable: " + path);
            }
            return;
        }

        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Model artifact does not exist: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Model artifact is not readable: " + path);
        }
    }

    private void validateExpectedStringArray(
        JsonNode arrayNode,
        List<String> expected,
        Path path,
        String fieldName
    ) {
        if (!arrayNode.isArray()) {
            throw new IllegalStateException("Invalid metadata in " + path + ": " + fieldName + " must be an array");
        }

        List<String> actual = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            actual.add(validateRequiredText(node, path, fieldName + "[]"));
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                "Invalid metadata in " + path + ": " + fieldName + " must be " + expected
            );
        }
    }

    private String validateRequiredText(JsonNode node, Path path, String fieldName) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("Invalid artifact field " + fieldName + " in " + path);
        }
        return node.asText();
    }

    private String optionalText(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
