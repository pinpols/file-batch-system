package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DefaultStepRegistry implements StepRegistry {

    private final Map<String, Step> steps;

    @Override
    public Optional<Step> find(String stepCode) {
        if (!StringUtils.hasText(stepCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(steps.get(stepCode));
    }
}
