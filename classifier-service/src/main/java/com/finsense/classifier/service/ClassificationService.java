package com.finsense.classifier.service;

import com.finsense.classifier.config.ClassificationProperties;
import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ClassificationService {

    private final ClassificationStrategy activeStrategy;

    public ClassificationService(
        List<ClassificationStrategy> strategies,
        ClassificationProperties properties
    ) {
        Map<String, ClassificationStrategy> strategyById = new HashMap<>();
        for (ClassificationStrategy strategy : strategies) {
            String key = strategy.id().toLowerCase(Locale.ROOT);
            if (strategyById.putIfAbsent(key, strategy) != null) {
                throw new IllegalStateException("Duplicate strategy id: " + strategy.id());
            }
        }

        String configuredStrategy = properties.getStrategy().toLowerCase(Locale.ROOT);
        this.activeStrategy = strategyById.get(configuredStrategy);
        if (this.activeStrategy == null) {
            throw new IllegalStateException(
                "Unsupported classification strategy: " + properties.getStrategy()
            );
        }
    }

    public ClassificationDecision classify(ClassificationInput input) {
        return activeStrategy.classify(input);
    }
}
