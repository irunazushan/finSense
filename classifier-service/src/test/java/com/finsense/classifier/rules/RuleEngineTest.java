package com.finsense.classifier.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsense.classifier.config.RulesLoader;
import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.model.TransactionCategory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RuleEngineTest {

    private RuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        Map<String, TransactionCategory> mccRules = new LinkedHashMap<>();
        mccRules.put("5812", TransactionCategory.FOOD_AND_DRINKS);
        mccRules.put("4111", TransactionCategory.TRANSPORT);

        Map<TransactionCategory, List<String>> keywordRules = new LinkedHashMap<>();
        keywordRules.put(TransactionCategory.FOOD_AND_DRINKS, List.of("coffee", "restaurant"));
        keywordRules.put(TransactionCategory.TRANSPORT, List.of("taxi", "metro"));
        keywordRules.put(TransactionCategory.SHOPPING, List.of("store", "market"));

        RuleSet ruleSet = new RuleSet(
            mccRules,
            keywordRules,
            List.of(TransactionCategory.FOOD_AND_DRINKS, TransactionCategory.TRANSPORT, TransactionCategory.SHOPPING),
            new RuleSet.ConfidenceConfig(0.95, 0.85, 0.05, 0.99)
        );

        RulesLoader loader = Mockito.mock(RulesLoader.class);
        Mockito.when(loader.getRuleSet()).thenReturn(ruleSet);
        ruleEngine = new RuleEngine(loader, new TextNormalizer());
    }

    @Test
    void mccMatchHasPriorityOverKeywords() {
        ClassificationDecision result = ruleEngine.classify(input("taxi coffee", "5812"));
        assertThat(result.category()).isEqualTo(TransactionCategory.FOOD_AND_DRINKS);
        assertThat(result.confidence()).isEqualTo(0.99);
    }

    @Test
    void keywordMatchUsedWhenMccMissing() {
        ClassificationDecision result = ruleEngine.classify(input("taxi metro", null));
        assertThat(result.category()).isEqualTo(TransactionCategory.TRANSPORT);
        assertThat(result.confidence()).isEqualTo(0.95);
    }

    @Test
    void tieResolvedByConfiguredCategoryOrder() {
        ClassificationDecision result = ruleEngine.classify(input("coffee taxi", null));
        assertThat(result.category()).isEqualTo(TransactionCategory.FOOD_AND_DRINKS);
        assertThat(result.confidence()).isEqualTo(0.90);
    }

    @Test
    void returnsUndefinedWhenNoRuleMatched() {
        ClassificationDecision result = ruleEngine.classify(input("unknown merchant", null));
        assertThat(result.category()).isEqualTo(TransactionCategory.UNDEFINED);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    private ClassificationInput input(String description, String mccCode) {
        return new ClassificationInput(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            BigDecimal.TEN,
            description,
            null,
            mccCode
        );
    }
}
