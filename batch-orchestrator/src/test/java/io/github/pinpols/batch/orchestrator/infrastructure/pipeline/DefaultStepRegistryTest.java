package io.github.pinpols.batch.orchestrator.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.domain.pipeline.Step;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultStepRegistryTest {

  @Test
  void shouldFindRegisteredStep() {
    Step mockStep = mock(Step.class);
    DefaultStepRegistry registry = new DefaultStepRegistry(Map.of("PARSE", mockStep));

    Optional<Step> found = registry.find("PARSE");
    assertThat(found).isPresent().contains(mockStep);
  }

  @Test
  void shouldReturnEmptyForUnregisteredStepCode() {
    DefaultStepRegistry registry = new DefaultStepRegistry(Map.of());

    assertThat(registry.find("UNKNOWN")).isEmpty();
  }

  @Test
  void shouldReturnEmptyForNullStepCode() {
    DefaultStepRegistry registry = new DefaultStepRegistry(Map.of());

    assertThat(registry.find(null)).isEmpty();
  }

  @Test
  void shouldSupportMultipleRegisteredSteps() {
    Step step1 = mock(Step.class);
    Step step2 = mock(Step.class);
    DefaultStepRegistry registry =
        new DefaultStepRegistry(Map.of("STEP_A", step1, "STEP_B", step2));

    assertThat(registry.find("STEP_A")).isPresent().contains(step1);
    assertThat(registry.find("STEP_B")).isPresent().contains(step2);
  }
}
