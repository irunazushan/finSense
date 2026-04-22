package com.finsense.classifier.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.model.TransactionCategory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OnnxFeaturePreprocessorTest {

    private final OnnxFeaturePreprocessor preprocessor = new OnnxFeaturePreprocessor();
    private final OnnxModelArtifacts artifacts = new OnnxModelArtifacts(
        Path.of("model.onnx"),
        Path.of("labels.json"),
        Path.of("metadata.json"),
        List.of(TransactionCategory.values()),
        "__MISSING_MCC__",
        50000.0,
        "signed_log1p",
        "tfidf_logistic_regression",
        15,
        "test-dataset"
    );

    @Test
    void preprocessesMissingTextAndMcc() {
        ClassificationInput input = new ClassificationInput(
            UUID.randomUUID(),
            BigDecimal.ZERO,
            null,
            "  ",
            " "
        );

        OnnxFeatureVector features = preprocessor.preprocess(input, artifacts);

        assertThat(features.text()).isEmpty();
        assertThat(features.mccCode()).isEqualTo("__MISSING_MCC__");
        assertThat(features.amount()).isZero();
    }

    @Test
    void concatenatesTextAndAppliesSignedLogWithClipping() {
        ClassificationInput input = new ClassificationInput(
            UUID.randomUUID(),
            new BigDecimal("-90000"),
            "coffee payment",
            "Starbucks Cafe",
            "5812"
        );

        OnnxFeatureVector features = preprocessor.preprocess(input, artifacts);

        assertThat(features.text()).isEqualTo("coffee payment Starbucks Cafe");
        assertThat(features.mccCode()).isEqualTo("5812");
        assertThat(features.amount()).isCloseTo((float) -Math.log1p(50000.0), within(0.0001f));
    }

    private static org.assertj.core.data.Offset<Float> within(float value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
