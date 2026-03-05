package com.finsense.classifier.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.finsense.classifier.model.TransactionCategory;
import com.finsense.classifier.rules.RuleSet;
import com.finsense.classifier.rules.TextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class RulesLoader {

    private final ResourceLoader resourceLoader;
    private final TextNormalizer textNormalizer;
    private final RuleSet ruleSet;

    public RulesLoader(
        ClassificationProperties properties,
        ResourceLoader resourceLoader,
        TextNormalizer textNormalizer
    ) {
        this.resourceLoader = resourceLoader;
        this.textNormalizer = textNormalizer;
        this.ruleSet = load(properties.getRulesFile());
    }

    public RuleSet getRuleSet() {
        return ruleSet;
    }

    RuleSet load(String location) {
        RulesDocument document = readDocument(location);
        validateConfidence(document.confidence);

        Map<String, TransactionCategory> mccRules = buildMccRules(document.mcc);
        Map<TransactionCategory, List<String>> keywordRules = new LinkedHashMap<>();
        Set<TransactionCategory> priorityOrder = new LinkedHashSet<>();

        if (document.keywords != null) {
            for (KeywordDocument keywordRule : document.keywords) {
                TransactionCategory category = parseCategory(keywordRule.category, "keywords.category");
                priorityOrder.add(category);

                List<String> normalizedWords = keywordRules.computeIfAbsent(category, key -> new ArrayList<>());
                if (keywordRule.words == null) {
                    continue;
                }
                for (String rawWord : keywordRule.words) {
                    String normalized = textNormalizer.normalize(rawWord);
                    if (!normalized.isBlank() && !normalizedWords.contains(normalized)) {
                        normalizedWords.add(normalized);
                    }
                }
            }
        }

        RuleSet.ConfidenceConfig confidence = new RuleSet.ConfidenceConfig(
            document.confidence.mccBase,
            document.confidence.keywordBase,
            document.confidence.boostPerMatch,
            document.confidence.max
        );

        return new RuleSet(
            Collections.unmodifiableMap(mccRules),
            Collections.unmodifiableMap(keywordRules),
            List.copyOf(priorityOrder),
            confidence
        );
    }

    private RulesDocument readDocument(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                throw new IllegalStateException("Rules file does not exist: " + location);
            }

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.findAndRegisterModules();

            try (InputStream inputStream = resource.getInputStream()) {
                RulesDocument document = mapper.readValue(inputStream, RulesDocument.class);
                if (document == null) {
                    throw new IllegalStateException("Rules file is empty: " + location);
                }
                return document;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load rules from: " + location, ex);
        }
    }

    private Map<String, TransactionCategory> buildMccRules(Map<String, String> mccRulesRaw) {
        Map<String, TransactionCategory> mccRules = new LinkedHashMap<>();
        if (mccRulesRaw == null) {
            return mccRules;
        }

        for (Map.Entry<String, String> entry : mccRulesRaw.entrySet()) {
            String mccCode = entry.getKey() == null ? "" : entry.getKey().trim();
            if (mccCode.isBlank()) {
                throw new IllegalStateException("mcc code cannot be blank");
            }
            mccRules.put(mccCode, parseCategory(entry.getValue(), "mcc." + mccCode));
        }
        return mccRules;
    }

    private TransactionCategory parseCategory(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Category is empty for: " + fieldName);
        }
        try {
            return TransactionCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported category '" + value + "' for: " + fieldName, ex);
        }
    }

    private void validateConfidence(ConfidenceDocument confidence) {
        if (confidence == null) {
            throw new IllegalStateException("confidence section is required");
        }
        if (confidence.max < 0.0 || confidence.max > 1.0) {
            throw new IllegalStateException("confidence.max must be in range [0.0, 1.0]");
        }
        if (confidence.mccBase < 0.0 || confidence.mccBase > confidence.max) {
            throw new IllegalStateException("confidence.mcc_base must be in range [0.0, max]");
        }
        if (confidence.keywordBase < 0.0 || confidence.keywordBase > confidence.max) {
            throw new IllegalStateException("confidence.keyword_base must be in range [0.0, max]");
        }
        if (confidence.boostPerMatch < 0.0) {
            throw new IllegalStateException("confidence.boost_per_match must be >= 0.0");
        }
    }

    static class RulesDocument {
        public Map<String, String> mcc = new LinkedHashMap<>();
        public List<KeywordDocument> keywords = new ArrayList<>();
        public ConfidenceDocument confidence = new ConfidenceDocument();
    }

    static class KeywordDocument {
        public String category;
        public List<String> words = new ArrayList<>();
    }

    static class ConfidenceDocument {
        @JsonProperty("mcc_base")
        public double mccBase;

        @JsonProperty("keyword_base")
        public double keywordBase;

        @JsonProperty("boost_per_match")
        public double boostPerMatch;

        public double max;
    }
}
