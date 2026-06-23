package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskCapabilityTest {

  @Test
  void shouldBuildViaShortcut() {
    TaskCapability cap = TaskCapability.of(ResourceKind.NET, ResourceKind.DISK);
    assertThat(cap.resourceKinds()).containsExactlyInAnyOrder(ResourceKind.NET, ResourceKind.DISK);
    assertThat(cap.idempotent()).isTrue();
    assertThat(cap.cancellable()).isTrue();
    assertThat(cap.recommendedTimeout()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void shouldRejectEmptyResourceKinds() {
    assertThatThrownBy(() -> new TaskCapability(Set.of(), false, false, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resourceKinds must not be empty");
  }

  @Test
  void shouldRejectNonPositiveTimeout() {
    assertThatThrownBy(
            () -> new TaskCapability(Set.of(ResourceKind.CPU), false, false, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recommendedTimeout must be positive");
  }

  @Test
  void shouldDefensivelyCopyResourceKinds() {
    var mutable = new java.util.HashSet<>(Set.of(ResourceKind.CPU));
    TaskCapability cap = new TaskCapability(mutable, false, false, Duration.ofSeconds(1));
    mutable.add(ResourceKind.NET);
    assertThat(cap.resourceKinds()).containsExactly(ResourceKind.CPU);
  }
}
