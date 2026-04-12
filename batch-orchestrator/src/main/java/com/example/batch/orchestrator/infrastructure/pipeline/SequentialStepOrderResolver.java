package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.orchestrator.domain.pipeline.StepDefinition;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SequentialStepOrderResolver {

  public List<StepDefinition> sort(List<StepDefinition> steps) {
    return steps.stream()
        .sorted(
            Comparator.comparing(
                step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
        .toList();
  }
}
