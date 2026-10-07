package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务能力声明:快捷构造默认值, 参数校验与资源类型防御性拷贝")
class TaskCapabilityTest {

  @Test
  @DisplayName("快捷构造:资源类型保留, 幂等与可取消默认开启, 超时取默认时长")
  void shouldBuildViaShortcut() {
    TaskCapability cap = TaskCapability.of(ResourceKind.NET, ResourceKind.DISK);
    assertThat(cap.resourceKinds()).containsExactlyInAnyOrder(ResourceKind.NET, ResourceKind.DISK);
    assertThat(cap.idempotent()).isTrue();
    assertThat(cap.cancellable()).isTrue();
    assertThat(cap.recommendedTimeout()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("资源类型为空集:构造被拒绝, 并提示不能为空")
  void shouldRejectEmptyResourceKinds() {
    assertThatThrownBy(() -> new TaskCapability(Set.of(), false, false, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resourceKinds must not be empty");
  }

  @Test
  @DisplayName("推荐超时非正数:构造被拒绝, 并提示必须为正")
  void shouldRejectNonPositiveTimeout() {
    assertThatThrownBy(
            () -> new TaskCapability(Set.of(ResourceKind.CPU), false, false, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recommendedTimeout must be positive");
  }

  @Test
  @DisplayName("外部集合事后变更:已构造实例的资源类型不受影响")
  void shouldDefensivelyCopyResourceKinds() {
    var mutable = new java.util.HashSet<>(Set.of(ResourceKind.CPU));
    TaskCapability cap = new TaskCapability(mutable, false, false, Duration.ofSeconds(1));
    mutable.add(ResourceKind.NET);
    assertThat(cap.resourceKinds()).containsExactly(ResourceKind.CPU);
  }
}
