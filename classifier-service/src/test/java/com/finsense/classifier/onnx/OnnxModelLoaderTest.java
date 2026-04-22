package com.finsense.classifier.onnx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsense.classifier.config.ClassificationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OnnxModelLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsValidArtifacts() throws IOException {
        Path modelDir = Files.createTempDirectory("onnx-model");
        writeArtifacts(modelDir, validLabels(), validMetadata());

        OnnxModelLoader loader = new OnnxModelLoader(objectMapper, propertiesFor(modelDir));

        assertThat(loader.getArtifacts().labels()).hasSize(9);
        assertThat(loader.getArtifacts().missingMccToken()).isEqualTo("__MISSING_MCC__");
        assertThat(loader.getArtifacts().amountClipMax()).isEqualTo(50000.0);
    }

    @Test
    void failsWhenLabelsDoNotMatchEnum() throws IOException {
        Path modelDir = Files.createTempDirectory("onnx-model-bad-labels");
        writeArtifacts(
            modelDir,
            """
            {"labels":["FOOD_AND_DRINKS","TRANSPORT"]}
            """,
            validMetadata()
        );

        assertThatThrownBy(() -> new OnnxModelLoader(objectMapper, propertiesFor(modelDir)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must exactly match");
    }

    @Test
    void failsWhenModelDirectoryIsMissing() {
        Path missingDir = Path.of("build", "missing-model-dir").toAbsolutePath();

        assertThatThrownBy(() -> new OnnxModelLoader(objectMapper, propertiesFor(missingDir)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Model directory does not exist");
    }

    @Test
    void failsWhenMetadataPreprocessingDoesNotMatch() throws IOException {
        Path modelDir = Files.createTempDirectory("onnx-model-bad-metadata");
        writeArtifacts(
            modelDir,
            validLabels(),
            """
            {
              "inputs": ["text", "mccCode", "amount"],
              "missing_mcc_token": "__MISSING_MCC__",
              "amount_preprocessing": {
                "transform": "log1p",
                "clip_max": 50000.0
              }
            }
            """
        );

        assertThatThrownBy(() -> new OnnxModelLoader(objectMapper, propertiesFor(modelDir)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("amount_preprocessing.transform");
    }

    private void writeArtifacts(Path modelDir, String labels, String metadata) throws IOException {
        Files.writeString(modelDir.resolve(OnnxModelLoader.MODEL_FILENAME), "fixture");
        Files.writeString(modelDir.resolve(OnnxModelLoader.LABELS_FILENAME), labels);
        Files.writeString(modelDir.resolve(OnnxModelLoader.METADATA_FILENAME), metadata);
    }

    private ClassificationProperties propertiesFor(Path modelDir) {
        ClassificationProperties properties = new ClassificationProperties();
        properties.setStrategy("ml");
        properties.getModel().setDir(modelDir.toString());
        return properties;
    }

    private String validLabels() {
        return """
            {
              "labels": [
                "BANKING_AND_FEES",
                "BILLS_AND_GOVERNMENT",
                "ENTERTAINMENT",
                "FOOD_AND_DRINKS",
                "GROCERIES",
                "HEALTH",
                "RETAIL_SHOPPING",
                "TRANSPORT",
                "UNDEFINED"
              ]
            }
            """;
    }

    private String validMetadata() {
        return """
            {
              "model_type": "tfidf_logistic_regression",
              "inputs": ["text", "mccCode", "amount"],
              "missing_mcc_token": "__MISSING_MCC__",
              "amount_preprocessing": {
                "transform": "signed_log1p",
                "clip_max": 50000.0
              },
              "target_opset": 15,
              "training_data": {
                "dataset_id": "test-dataset"
              }
            }
            """;
    }
}
