package com.finsense.classifier.service;

import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.onnx.OnnxFeaturePreprocessor;
import com.finsense.classifier.onnx.OnnxFeatureVector;
import com.finsense.classifier.onnx.OnnxInferenceEngine;
import com.finsense.classifier.onnx.OnnxModelArtifacts;
import com.finsense.classifier.onnx.OnnxModelLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.classification", name = "strategy", havingValue = "ml")
public class MlOnnxClassificationStrategy implements ClassificationStrategy {

    private static final Logger log = LoggerFactory.getLogger(MlOnnxClassificationStrategy.class);
    private static final String SOURCE = "ML";

    private final OnnxFeaturePreprocessor preprocessor;
    private final OnnxInferenceEngine inferenceEngine;
    private final OnnxModelArtifacts artifacts;

    public MlOnnxClassificationStrategy(
        OnnxFeaturePreprocessor preprocessor,
        OnnxInferenceEngine inferenceEngine,
        OnnxModelLoader modelLoader
    ) {
        this.preprocessor = preprocessor;
        this.inferenceEngine = inferenceEngine;
        this.artifacts = modelLoader.getArtifacts();
    }

    @Override
    public String id() {
        return "ml";
    }

    @Override
    public ClassificationDecision classify(ClassificationInput input) {
        try {
            OnnxFeatureVector features = preprocessor.preprocess(input, artifacts);
            OnnxInferenceEngine.Prediction prediction = inferenceEngine.predict(features);
            return new ClassificationDecision(
                input.transactionId(),
                prediction.category(),
                prediction.confidence(),
                SOURCE
            );
        } catch (RuntimeException ex) {
            log.error(
                "ML classification failed transactionId={} strategy=ml message={}",
                input.transactionId(),
                ex.getMessage(),
                ex
            );
            throw ex;
        }
    }
}
