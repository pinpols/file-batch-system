package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchTypeTest {

  @Test
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
  void codeShouldMatchEnumName() {
    for (BatchType type : BatchType.values()) {
      assertThat(type.code()).as("code for %s", type.name()).isEqualTo(type.name());
    }
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (BatchType type : BatchType.values()) {
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  // ─── 投影完整性:JobType / PipelineType 的每个枚举值都能映射到一个 BatchType ─────────────

  @Test
  void jobTypeProjectsToBatchType() {
    assertThat(JobType.GENERAL.batchType()).isEqualTo(BatchType.GENERAL);
    assertThat(JobType.IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(JobType.EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(JobType.DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
    assertThat(JobType.WORKFLOW.batchType()).isEqualTo(BatchType.WORKFLOW);
    for (JobType t : JobType.values()) {
      assertThat(t.batchType()).as("%s 投影不能为 null", t).isNotNull();
    }
  }

  @Test
  void pipelineTypeProjectsToBatchType() {
    assertThat(PipelineType.IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(PipelineType.EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(PipelineType.DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
    for (PipelineType t : PipelineType.values()) {
      assertThat(t.batchType()).as("%s 投影不能为 null", t).isNotNull();
    }
  }

  @Test
  void jobTypeAndPipelineTypeAgreeOnSharedBusinessTypes() {
    // 两个枚举对同一业务类型的投影必须一致,否则 console 配置和 worker 执行会脱钩
    assertThat(JobType.IMPORT.batchType()).isEqualTo(PipelineType.IMPORT.batchType());
    assertThat(JobType.EXPORT.batchType()).isEqualTo(PipelineType.EXPORT.batchType());
    assertThat(JobType.DISPATCH.batchType()).isEqualTo(PipelineType.DISPATCH.batchType());
  }
}
