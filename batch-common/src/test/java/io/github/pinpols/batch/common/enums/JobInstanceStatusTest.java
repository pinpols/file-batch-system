package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JobInstanceStatus 作业实例状态: 编码 / 标签声明,以及活跃 / 成功 / 终态 分类的完备性")
class JobInstanceStatusTest {

  @Test
  @DisplayName("各作业实例状态的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(JobInstanceStatus.CREATED.code()).isEqualTo("CREATED");
    assertThat(JobInstanceStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(JobInstanceStatus.READY.code()).isEqualTo("READY");
    assertThat(JobInstanceStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(JobInstanceStatus.PARTIAL_FAILED.code()).isEqualTo("PARTIAL_FAILED");
    assertThat(JobInstanceStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(JobInstanceStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(JobInstanceStatus.CANCELLED.code()).isEqualTo("CANCELLED");
    assertThat(JobInstanceStatus.TERMINATED.code()).isEqualTo("TERMINATED");
  }

  @Test
  @DisplayName("每个作业实例状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个作业实例状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllInstanceStatuses() {
    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      assertThat(status.code()).as("code for %s", status.name()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("作业实例状态共十二项,含暂停态且数量不得静默变化")
  void shouldContainTwelveValues() {
    // 9 业务态 + ADR-026 dry-run 2 个终态 + ADR-044 可逆 PAUSED
    assertThat(JobInstanceStatus.values()).hasSize(12);
    assertThat(JobInstanceStatus.PAUSED.code()).isEqualTo("PAUSED");
  }

  @Test
  @DisplayName("活跃与终态分类互斥且并集覆盖全部状态,暂停归入活跃")
  void shouldClassifyEveryStatusExactlyOnce_whenCheckingLifecycleCatalog() {
    Set<String> allCodes = new HashSet<>();
    Arrays.stream(JobInstanceStatus.values()).map(JobInstanceStatus::code).forEach(allCodes::add);

    assertThat(JobInstanceStatus.terminalCodes())
        .doesNotContainAnyElementsOf(JobInstanceStatus.activeCodes());
    assertThat(JobInstanceStatus.terminalCodes())
        .containsExactlyInAnyOrderElementsOf(
            union(JobInstanceStatus.successCodes(), JobInstanceStatus.unsuccessfulTerminalCodes()));
    assertThat(union(JobInstanceStatus.terminalCodes(), JobInstanceStatus.activeCodes()))
        .containsExactlyInAnyOrderElementsOf(allCodes);
    assertThat(JobInstanceStatus.activeCodes()).contains(JobInstanceStatus.PAUSED.code());
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> union = new HashSet<>(first);
    union.addAll(second);
    return union;
  }
}
