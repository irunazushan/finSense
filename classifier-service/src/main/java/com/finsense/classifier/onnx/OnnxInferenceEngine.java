package com.finsense.classifier.onnx;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxSequence;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.finsense.classifier.model.TransactionCategory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.classification", name = "strategy", havingValue = "ml")
public class OnnxInferenceEngine implements DisposableBean {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final OnnxModelArtifacts artifacts;

    public OnnxInferenceEngine(OnnxModelLoader modelLoader) {
        this.artifacts = modelLoader.getArtifacts();
        try {
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(artifacts.modelPath().toString(), new OrtSession.SessionOptions());
        } catch (OrtException ex) {
            throw new IllegalStateException("Failed to initialize ONNX runtime session from " + artifacts.modelPath(), ex);
        }
    }

    public Prediction predict(OnnxFeatureVector features) {
        try (
            OnnxTensor textTensor = OnnxTensor.createTensor(environment, new String[][] {{ features.text() }});
            OnnxTensor mccTensor = OnnxTensor.createTensor(environment, new String[][] {{ features.mccCode() }});
            OnnxTensor amountTensor = OnnxTensor.createTensor(environment, new float[][] {{ features.amount() }});
            OrtSession.Result result = session.run(
                Map.of(
                    "text", textTensor,
                    "mccCode", mccTensor,
                    "amount", amountTensor
                )
            )
        ) {
            float[] probabilities = extractProbabilityRow(result, artifacts.labels());
            int bestIndex = 0;
            for (int index = 1; index < probabilities.length; index++) {
                if (probabilities[index] > probabilities[bestIndex]) {
                    bestIndex = index;
                }
            }

            return new Prediction(artifacts.labels().get(bestIndex), probabilities[bestIndex]);
        } catch (OrtException ex) {
            throw new IllegalStateException("Failed to run ONNX inference using model " + artifacts.modelPath(), ex);
        }
    }

    static float[] extractProbabilityRow(OrtSession.Result result, List<TransactionCategory> labels) throws OrtException {
        for (Map.Entry<String, OnnxValue> entry : result) {
            float[] row = extractProbabilityRow(entry.getValue(), labels);
            if (row != null) {
                return row;
            }
        }
        throw new IllegalStateException("ONNX model did not return a probability matrix");
    }

    static float[] extractProbabilityRow(OnnxValue value, List<TransactionCategory> labels) throws OrtException {
        if (value instanceof OnnxTensor tensor) {
            return extractProbabilityRow(tensor.getValue(), labels);
        }
        if (value instanceof OnnxMap map) {
            return extractProbabilityRow(map.getValue(), labels);
        }
        if (value instanceof OnnxSequence sequence) {
            return extractProbabilityRow(sequence.getValue(), labels);
        }
        return null;
    }

    static float[] extractProbabilityRow(Object value, List<TransactionCategory> labels) {
        if (value instanceof float[][] matrix && matrix.length > 0 && matrix[0].length == labels.size()) {
            return matrix[0].clone();
        }
        if (value instanceof double[][] matrix && matrix.length > 0 && matrix[0].length == labels.size()) {
            return toFloatArray(matrix[0]);
        }
        if (value instanceof float[] row && row.length == labels.size()) {
            return row.clone();
        }
        if (value instanceof double[] row && row.length == labels.size()) {
            return toFloatArray(row);
        }
        if (value instanceof Map<?, ?> map) {
            return extractProbabilityRowFromMap(map, labels);
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> firstMap) {
            return extractProbabilityRowFromMap(firstMap, labels);
        }
        return null;
    }

    static float[] extractProbabilityRowFromMap(Map<?, ?> rawMap, List<TransactionCategory> labels) {
        Map<String, Number> probabilities = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                probabilities.put(key, value);
            }
        }

        if (probabilities.size() != labels.size()) {
            return null;
        }

        float[] row = new float[labels.size()];
        for (int index = 0; index < labels.size(); index++) {
            Number probability = probabilities.get(labels.get(index).name());
            if (probability == null) {
                return null;
            }
            row[index] = probability.floatValue();
        }
        return row;
    }

    private static float[] toFloatArray(double[] values) {
        float[] floats = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            floats[index] = (float) values[index];
        }
        return floats;
    }

    @Override
    public void destroy() throws OrtException {
        session.close();
        environment.close();
    }

    public record Prediction(
        TransactionCategory category,
        float confidence
    ) {
    }
}
