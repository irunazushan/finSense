package com.finsense.classifier.onnx;

public record OnnxFeatureVector(
    String text,
    String mccCode,
    float amount
) {
}
