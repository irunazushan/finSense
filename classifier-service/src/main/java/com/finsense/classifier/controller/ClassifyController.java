package com.finsense.classifier.controller;

import com.finsense.classifier.dto.ClassificationResult;
import com.finsense.classifier.dto.ClassifyRequest;
import com.finsense.classifier.model.ClassificationDecision;
import com.finsense.classifier.model.ClassificationInput;
import com.finsense.classifier.service.ClassificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Classification", description = "Transaction classification API")
public class ClassifyController {

    private final ClassificationService classificationService;

    public ClassifyController(ClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    @PostMapping("/classify")
    @Operation(summary = "Classify transaction by rules")
    public ClassificationResult classify(@Valid @RequestBody ClassifyRequest request) {
        ClassificationDecision decision = classificationService.classify(
            new ClassificationInput(
                request.transactionId(),
                request.amount(),
                request.description(),
                request.merchantName(),
                request.mccCode()
            )
        );

        return new ClassificationResult(
            decision.transactionId(),
            decision.category().name(),
            decision.confidence(),
            decision.source()
        );
    }
}
