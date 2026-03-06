package com.finsense.classifier.rules;

import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.model.TransactionCategory;
import com.finsense.classifier.config.RulesLoader;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {

    private static final String RULE_SOURCE = "RULE";

    private final RuleSet ruleSet;
    private final TextNormalizer textNormalizer;

    public RuleEngine(RulesLoader rulesLoader, TextNormalizer textNormalizer) {
        this.ruleSet = rulesLoader.getRuleSet();
        this.textNormalizer = textNormalizer;
    }

    public ClassificationDecision classify(ClassificationInput input) {
        TransactionCategory mccCategory = resolveByMcc(input.mccCode());
        String normalizedText = buildNormalizedText(input.description(), input.merchantName());

        if (mccCategory != null) {
            int sameCategoryHits = countKeywordHits(mccCategory, normalizedText);
            int maxOtherCategoryHits = maxOtherCategoryHits(mccCategory, normalizedText);
            double base = sameCategoryHits > 0
                ? ruleSet.confidence().mccBaseConfirmed()
                : ruleSet.confidence().mccBaseUnconfirmed();
            double confidence = capMcc(
                base
                    + (ruleSet.confidence().boostPerMatch() * sameCategoryHits)
                    - (ruleSet.confidence().contradictionPenalty() * maxOtherCategoryHits)
            );
            return new ClassificationDecision(input.transactionId(), mccCategory, confidence, RULE_SOURCE);
        }

        Candidate best = null;
        for (TransactionCategory category : ruleSet.keywordPriority()) {
            int hits = countKeywordHits(category, normalizedText);
            if (hits <= 0) {
                continue;
            }

            double confidence = cap(ruleSet.confidence().keywordBase()
                + (ruleSet.confidence().boostPerMatch() * hits));
            Candidate candidate = new Candidate(category, hits, confidence);

            if (best == null || candidate.confidence() > best.confidence() ||
                (Double.compare(candidate.confidence(), best.confidence()) == 0 && candidate.hits() > best.hits())) {
                best = candidate;
            }
        }

        if (best == null) {
            return new ClassificationDecision(input.transactionId(), TransactionCategory.UNDEFINED, 0.0, RULE_SOURCE);
        }

        return new ClassificationDecision(input.transactionId(), best.category(), best.confidence(), RULE_SOURCE);
    }

    private TransactionCategory resolveByMcc(String mccCode) {
        if (mccCode == null || mccCode.isBlank()) {
            return null;
        }
        return ruleSet.mccRules().get(mccCode.trim());
    }

    private String buildNormalizedText(String description, String merchantName) {
        String normalizedDescription = textNormalizer.normalize(description);
        String normalizedMerchantName = textNormalizer.normalize(merchantName);

        if (normalizedDescription.isEmpty()) {
            return normalizedMerchantName;
        }
        if (normalizedMerchantName.isEmpty()) {
            return normalizedDescription;
        }
        return normalizedDescription + " " + normalizedMerchantName;
    }

    private int countKeywordHits(TransactionCategory category, String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return 0;
        }

        List<String> keywords = ruleSet.keywordRules().get(category);
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }

        int hits = 0;
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }

    private double cap(double value) {
        return Math.min(value, ruleSet.confidence().max());
    }

    private double capMcc(double value) {
        return Math.max(ruleSet.confidence().mccMin(), cap(value));
    }

    private int maxOtherCategoryHits(TransactionCategory selectedCategory, String normalizedText) {
        int max = 0;
        for (TransactionCategory category : ruleSet.keywordPriority()) {
            if (category == selectedCategory) {
                continue;
            }
            max = Math.max(max, countKeywordHits(category, normalizedText));
        }
        return max;
    }

    private record Candidate(
        TransactionCategory category,
        int hits,
        double confidence
    ) {
    }
}
