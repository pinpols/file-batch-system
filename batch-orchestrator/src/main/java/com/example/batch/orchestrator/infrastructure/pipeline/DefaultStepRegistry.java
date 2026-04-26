package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 步骤注册表默认实现：以 stepCode 为键在 Spring 容器注入的 {@code Map<String, Step>} 中查找步骤 Bean。 Bean name 即为
 * stepCode，由各 {@link Step} 实现类的 {@code @Component} 名称决定。
 */
@Component
@RequiredArgsConstructor
public class DefaultStepRegistry implements StepRegistry {

  private final Map<String, Step> steps;

  @Override
  public Optional<Step> find(String stepCode) {
    if (!Texts.hasText(stepCode)) {
      return Optional.empty();
    }
    return Optional.ofNullable(steps.get(stepCode));
  }
}
