package com.finsense.classifier.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsense.classifier.model.TransactionCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OnnxInferenceEngineTest {

    private static final List<TransactionCategory> LABELS = List.of(
        TransactionCategory.BANKING_AND_FEES,
        TransactionCategory.BILLS_AND_GOVERNMENT,
        TransactionCategory.ENTERTAINMENT,
        TransactionCategory.FOOD_AND_DRINKS,
        TransactionCategory.GROCERIES,
        TransactionCategory.HEALTH,
        TransactionCategory.RETAIL_SHOPPING,
        TransactionCategory.TRANSPORT,
        TransactionCategory.UNDEFINED
    );

    @Test
    void extractsProbabilityRowFromDenseTensorValue() {
        float[][] probabilities = {
            {0.10f, 0.20f, 0.05f, 0.30f, 0.05f, 0.05f, 0.15f, 0.05f, 0.05f}
        };

        float[] row = OnnxInferenceEngine.extractProbabilityRow(probabilities, LABELS);

        assertThat(row).containsExactly(probabilities[0]);
    }

    @Test
    void extractsProbabilityRowFromZipMapStyleOutput() {
        Map<String, Float> probabilities = Map.of(
            "BANKING_AND_FEES", 0.10f,
            "BILLS_AND_GOVERNMENT", 0.20f,
            "ENTERTAINMENT", 0.05f,
            "FOOD_AND_DRINKS", 0.30f,
            "GROCERIES", 0.05f,
            "HEALTH", 0.05f,
            "RETAIL_SHOPPING", 0.15f,
            "TRANSPORT", 0.05f,
            "UNDEFINED", 0.05f
        );

        float[] row = OnnxInferenceEngine.extractProbabilityRow(List.of(probabilities), LABELS);

        assertThat(row).containsExactly(0.10f, 0.20f, 0.05f, 0.30f, 0.05f, 0.05f, 0.15f, 0.05f, 0.05f);
    }
}
