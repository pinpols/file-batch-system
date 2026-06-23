package io.github.pinpols.batch.orchestrator.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.pipeline.StepDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SequentialStepOrderResolverTest {

  private SequentialStepOrderResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new SequentialStepOrderResolver();
  }

  @Test
  void shouldSortStepsByStepOrderAscending() {
    StepDefinition s1 = step("S1", 3);
    StepDefinition s2 = step("S2", 1);
    StepDefinition s3 = step("S3", 2);

    List<StepDefinition> sorted = resolver.sort(List.of(s1, s2, s3));

    assertThat(sorted).extracting(StepDefinition::getStepCode).containsExactly("S2", "S3", "S1");
  }

  @Test
  void shouldPlaceNullStepOrderLast() {
    StepDefinition withOrder = step("A", 1);
    StepDefinition withNull = step("B", null);

    List<StepDefinition> sorted = resolver.sort(List.of(withNull, withOrder));

    assertThat(sorted).extracting(StepDefinition::getStepCode).containsExactly("A", "B");
  }

  @Test
  void shouldHandleEmptyList() {
    assertThat(resolver.sort(List.of())).isEmpty();
  }

  @Test
  void shouldReturnSingleElementUnchanged() {
    StepDefinition only = step("ONLY", 1);
    assertThat(resolver.sort(List.of(only))).containsExactly(only);
  }

  @Test
  void shouldPreserveOriginalListUnchanged() {
    StepDefinition s1 = step("S1", 2);
    StepDefinition s2 = step("S2", 1);
    List<StepDefinition> original = List.of(s1, s2);

    resolver.sort(original);

    // original list should not be mutated
    assertThat(original).extracting(StepDefinition::getStepCode).containsExactly("S1", "S2");
  }

  // --- helpers ---

  private static StepDefinition step(String code, Integer order) {
    StepDefinition step = new StepDefinition();
    step.setStepCode(code);
    step.setStepOrder(order);
    step.setEnabled(true);
    return step;
  }
}
