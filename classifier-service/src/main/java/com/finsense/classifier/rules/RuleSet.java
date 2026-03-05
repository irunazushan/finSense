package com.finsense.classifier.rules;

import com.finsense.classifier.model.TransactionCategory;
import java.util.List;
import java.util.Map;

public record RuleSet(
    Map<String, TransactionCategory> mccRules,
    Map<TransactionCategory, List<String>> keywordRules,
    List<TransactionCategory> keywordPriority,
    ConfidenceConfig confidence
) {

    public record ConfidenceConfig(
        double mccBase,
        double keywordBase,
        double boostPerMatch,
        double max
    ) {
    }
}
