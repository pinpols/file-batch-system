package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultStepRegistry implements StepRegistry {

    private final Map<String, Step> steps;

    @Override
    public Optional<Step> find(String stepCode) {
        return Optional.ofNullable(steps.get(stepCode));
    }
}
