package com.finsense.classifier.service;

import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;

public interface ClassificationStrategy {

    String id();

    ClassificationDecision classify(ClassificationInput input);
}
