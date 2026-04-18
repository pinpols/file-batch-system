package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.orchestrator.domain.pipeline.StepDefinition;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 步骤顺序解析器：按 {@code stepOrder} 字段升序排列流水线步骤定义，{@code null} 排在末尾。
 */
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
