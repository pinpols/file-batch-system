package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParentVirtualTaskIdResolverTest {

  @Test
  void resolvesPositiveIdFromEffectiveParams() {
    assertThat(ParentVirtualTaskIdResolver.resolve(
            "{\"effectiveParams\":{\"_parentVirtualTaskId\":42}}"))
        .isEqualTo(42L);
  }

  @Test
  void ignoresMissingOrNonPositiveId() {
    assertThat(ParentVirtualTaskIdResolver.resolve(null)).isNull();
    assertThat(ParentVirtualTaskIdResolver.resolve("{}")).isNull();
    assertThat(ParentVirtualTaskIdResolver.resolve(
            "{\"effectiveParams\":{\"_parentVirtualTaskId\":0}}"))
        .isNull();
  }

  @Test
  void ignoresMalformedJson() {
    assertThat(ParentVirtualTaskIdResolver.resolve("not-json")).isNull();
  }
}
