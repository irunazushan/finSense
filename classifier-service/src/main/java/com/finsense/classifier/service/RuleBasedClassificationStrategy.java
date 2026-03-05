package com.finsense.classifier.service;

import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.rules.RuleEngine;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedClassificationStrategy implements ClassificationStrategy {

    private final RuleEngine ruleEngine;

    public RuleBasedClassificationStrategy(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String id() {
        return "rule";
    }

    @Override
    public ClassificationDecision classify(ClassificationInput input) {
        return ruleEngine.classify(input);
    }
}
