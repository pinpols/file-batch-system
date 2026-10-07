package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BatchType 业务批次类型: 编码 / 标签声明,以及作业类型与管道类型的投影一致性")
class BatchTypeTest {

  @Test
  @DisplayName("各业务批次类型的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(BatchType.IMPORT.code()).isEqualTo("IMPORT");
    assertThat(BatchType.EXPORT.code()).isEqualTo("EXPORT");
    assertThat(BatchType.PROCESS.code()).isEqualTo("PROCESS");
    assertThat(BatchType.DISPATCH.code()).isEqualTo("DISPATCH");
    assertThat(BatchType.SYNC.code()).isEqualTo("SYNC");
    assertThat(BatchType.GENERAL.code()).isEqualTo("GENERAL");
    assertThat(BatchType.WORKFLOW.code()).isEqualTo("WORKFLOW");
  }

  @Test
  @DisplayName("每个批次类型的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllBatchTypes() {
    for (BatchType type : BatchType.values()) {
      assertThat(type.code()).as("code for %s", type.name()).isEqualTo(type.name());
    }
  }

  @Test
  @DisplayName("每个批次类型都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (BatchType type : BatchType.values()) {
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  // ─── 投影完整性:JobType / PipelineType 的每个枚举值都能映射到一个 BatchType ─────────────

  @Test
  @DisplayName("作业类型全量投影到批次类型,通用与工作流类型也能落桶")
  void shouldProjectJobTypeToBatchType_whenMappingAllTypes() {
    assertThat(JobType.GENERAL.batchType()).isEqualTo(BatchType.GENERAL);
    assertThat(JobType.IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(JobType.EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(JobType.PROCESS.batchType()).isEqualTo(BatchType.PROCESS);
    assertThat(JobType.DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
    assertThat(JobType.WORKFLOW.batchType()).isEqualTo(BatchType.WORKFLOW);
    for (JobType t : JobType.values()) {
      assertThat(t.batchType()).as("%s 投影不能为 null", t).isNotNull();
    }
  }

  @Test
  @DisplayName("管道类型全量投影到批次类型,导入导出等业务类型逐一对应")
  void shouldProjectPipelineTypeToBatchType_whenMappingAllTypes() {
    assertThat(PipelineType.IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(PipelineType.EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(PipelineType.PROCESS.batchType()).isEqualTo(BatchType.PROCESS);
    assertThat(PipelineType.DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
    for (PipelineType t : PipelineType.values()) {
      assertThat(t.batchType()).as("%s 投影不能为 null", t).isNotNull();
    }
  }

  @Test
  @DisplayName("同一业务类型下作业类型与管道类型的投影结果必须一致")
  void shouldAgreeBetweenJobTypeAndPipelineType_whenProjectingSharedBusinessTypes() {
    // 两个枚举对同一业务类型的投影必须一致,否则 console 配置和 worker 执行会脱钩
    assertThat(JobType.IMPORT.batchType()).isEqualTo(PipelineType.IMPORT.batchType());
    assertThat(JobType.EXPORT.batchType()).isEqualTo(PipelineType.EXPORT.batchType());
    assertThat(JobType.PROCESS.batchType()).isEqualTo(PipelineType.PROCESS.batchType());
    assertThat(JobType.DISPATCH.batchType()).isEqualTo(PipelineType.DISPATCH.batchType());
  }
}
