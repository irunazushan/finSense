package com.finsense.classifier.onnx;

import com.finsense.classifier.model.ClassificationInput;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class OnnxFeaturePreprocessor {

    public OnnxFeatureVector preprocess(
        ClassificationInput input,
        OnnxModelArtifacts artifacts
    ) {
        String description = input.description() == null ? "" : input.description();
        String merchantName = input.merchantName() == null ? "" : input.merchantName();
        String text = (description + " " + merchantName).trim();
        String mccCode = input.mccCode() == null || input.mccCode().isBlank()
            ? artifacts.missingMccToken()
            : input.mccCode();

        return new OnnxFeatureVector(
            text,
            mccCode,
            transformAmount(input.amount(), artifacts.amountClipMax())
        );
    }

    float transformAmount(BigDecimal amount, double clipMax) {
        double numeric = amount == null ? 0.0 : amount.doubleValue();
        double clipped = Math.max(-clipMax, Math.min(clipMax, numeric));
        double transformed = Math.signum(clipped) * Math.log1p(Math.abs(clipped));
        return (float) transformed;
    }
}
