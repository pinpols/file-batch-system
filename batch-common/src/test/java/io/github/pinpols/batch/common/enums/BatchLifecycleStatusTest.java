package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BatchLifecycleStatus 公共生命周期: 编码 / 标签 / 终态判定,以及各具体状态向公共生命周期的投影")
class BatchLifecycleStatusTest {

  @Test
  @DisplayName("各生命周期状态的编码与约定值逐一对应")
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
  @DisplayName("每个生命周期状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (BatchLifecycleStatus status : BatchLifecycleStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个生命周期状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllStatuses() {
    for (BatchLifecycleStatus status : BatchLifecycleStatus.values()) {
      assertThat(status.code()).as("code for %s", status.name()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("仅成功 / 失败 / 取消 / 终止 视为终态,创建与运行中等状态不算终态")
  void shouldFlagTerminalOnlyForEndStates_whenJudgingAllStatuses() {
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
  @DisplayName("作业状态全量投影到公共生命周期,部分失败归入失败终态")
  void shouldProjectJobStatusToCommonLifecycle_whenMappingAllStatuses() {
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
  @DisplayName("作业实例状态全量投影,部分失败同样归入失败终态")
  void shouldProjectJobInstanceStatusToCommonLifecycle_whenMappingAllStatuses() {
    assertThat(JobInstanceStatus.PARTIAL_FAILED.lifecycle()).isEqualTo(BatchLifecycleStatus.FAILED);
    for (JobInstanceStatus s : JobInstanceStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  @DisplayName("分区状态全量投影,重试中归入运行态且投影结果非空")
  void shouldProjectPartitionStatusToCommonLifecycle_whenMappingAllStatuses() {
    assertThat(PartitionStatus.RETRYING.lifecycle()).isEqualTo(BatchLifecycleStatus.RUNNING);
    for (PartitionStatus s : PartitionStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  @DisplayName("任务状态全量投影到公共生命周期且无空缺")
  void shouldProjectTaskStatusToCommonLifecycle_whenMappingAllStatuses() {
    for (TaskStatus s : TaskStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  @DisplayName("步骤实例状态全量投影,重试中归入运行态且投影结果非空")
  void shouldProjectStepInstanceStatusToCommonLifecycle_whenMappingAllStatuses() {
    assertThat(StepInstanceStatus.RETRYING.lifecycle()).isEqualTo(BatchLifecycleStatus.RUNNING);
    for (StepInstanceStatus s : StepInstanceStatus.values()) {
      assertThat(s.lifecycle()).as("%s 投影不能为 null", s).isNotNull();
    }
  }

  @Test
  @DisplayName("具体状态为终态时,投影出的公共生命周期也必须判定为终态")
  void shouldMapTerminalStatesIntoTerminalLifecycle_whenProjecting() {
    // 任何"终态"语义的具体状态投影后,公共生命周期也必须 terminal()
    assertThat(JobStatus.SUCCESS.lifecycle().terminal()).isTrue();
    assertThat(JobStatus.PARTIAL_FAILED.lifecycle().terminal()).isTrue();
    assertThat(PartitionStatus.RETRYING.lifecycle().terminal()).isFalse();
    assertThat(TaskStatus.TERMINATED.lifecycle().terminal()).isTrue();
  }
}
