package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchLifecycleStatusTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(BatchLifecycleStatus.CREATED.code()).isEqualTo("CREATED");
    assertThat(BatchLifecycleStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(BatchLifecycleStatus.READY.code()).isEqualTo("READY");
    assertThat(BatchLifecycleStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(BatchLifecycleStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(BatchLifecycleStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(BatchLifecycleStatus.CANCELLED.code()).isEqualTo("CANCELLED");
    assertThat(BatchLifecycleStatus.TERMINATED.code()).isEqualTo("TERMINATED");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (BatchLifecycleStatus status : BatchLifecycleStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (BatchLifecycleStatus status : BatchLifecycleStatus.values()) {
      assertThat(status.code()).as("code for %s", status.name()).isEqualTo(status.name());
    }
  }

  @Test
  void terminalShouldFlagOnlyEndStates() {
    assertThat(BatchLifecycleStatus.SUCCESS.terminal()).isTrue();
    assertThat(BatchLifecycleStatus.FAILED.terminal()).isTrue();
    assertThat(BatchLifecycleStatus.CANCELLED.terminal()).isTrue();
    assertThat(BatchLifecycleStatus.TERMINATED.terminal()).isTrue();
    assertThat(BatchLifecycleStatus.CREATED.terminal()).isFalse();
    assertThat(BatchLifecycleStatus.WAITING.terminal()).isFalse();
    assertThat(BatchLifecycleStatus.READY.terminal()).isFalse();
    assertThat(BatchLifecycleStatus.RUNNING.terminal()).isFalse();
  }

  // ─── 投影完整性：5 个具体 Status 每个枚举值都能映射到一个 BatchLifecycleStatus ──────────

  @Test
  void jobStatusProjectsToCommonCode() {
    assertThat(JobStatus.CREATED.lifecycle()).isEqualTo(BatchLifecycleStatus.CREATED);
    assertThat(JobStatus.WAITING.lifecycle()).isEqualTo(BatchLifecycleStatus.WAITING);
    assertThat(JobStatus.READY.lifecycle()).isEqualTo(BatchLifecycleStatus.READY);
    assertThat(JobStatus.RUNNING.lifecycle()).isEqualTo(BatchLifecycleStatus.RUNNING);
    assertThat(JobStatus.PARTIAL_FAILED.lifecycle()).isEqualTo(BatchLifecycleStatus.FAILED);
    assertThat(JobStatus.SUCCESS.lifecycle()).isEqualTo(BatchLifecycleStatus.SUCCESS);
    assertThat(JobStatus.FAILED.lifecycle()).isEqualTo(BatchLifecycleStatus.FAILED);
    assertThat(JobStatus.CANCELLED.lifecycle()).isEqualTo(BatchLifecycleStatus.CANCELLED);
    assertThat(JobStatus.TERMINATED.lifecycle()).isEqualTo(BatchLifecycleStatus.TERMINATED);
    for (JobStatus s : JobStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  void jobInstanceStatusProjectsToCommonCode() {
    assertThat(JobInstanceStatus.PARTIAL_FAILED.lifecycle()).isEqualTo(BatchLifecycleStatus.FAILED);
    for (JobInstanceStatus s : JobInstanceStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  void partitionStatusProjectsToCommonCode() {
    assertThat(PartitionStatus.RETRYING.lifecycle()).isEqualTo(BatchLifecycleStatus.RUNNING);
    for (PartitionStatus s : PartitionStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  void taskStatusProjectsToCommonCode() {
    for (TaskStatus s : TaskStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  void stepInstanceStatusProjectsToCommonCode() {
    assertThat(StepInstanceStatus.RETRYING.lifecycle()).isEqualTo(BatchLifecycleStatus.RUNNING);
    for (StepInstanceStatus s : StepInstanceStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  void terminalStatesProjectIntoTerminalLifecycle() {
    // 任何"终态"语义的具体状态投影后,公共生命周期也必须 terminal()
    assertThat(JobStatus.SUCCESS.lifecycle().terminal()).isTrue();
    assertThat(JobStatus.PARTIAL_FAILED.lifecycle().terminal()).isTrue();
    assertThat(PartitionStatus.RETRYING.lifecycle().terminal()).isFalse();
    assertThat(TaskStatus.TERMINATED.lifecycle().terminal()).isTrue();
  }
}
