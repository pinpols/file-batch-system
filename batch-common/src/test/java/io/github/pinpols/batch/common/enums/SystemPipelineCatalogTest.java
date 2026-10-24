package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("系统 Pipeline 按 Job 类型解析固定阶段")
class SystemPipelineCatalogTest {

  @DisplayName("文件作业按类型解析 Pipeline 并保持固定阶段顺序")
  @Test
  void shouldResolveFilePipelineByJobTypeAndKeepFixedStageOrder() {
    assertThat(SystemPipelineCatalog.forJobType("IMPORT").orElseThrow().stages())
        .containsExactly("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK");
    assertThat(SystemPipelineCatalog.forJobType("BUNDLE_IMPORT").orElseThrow().code())
        .isEqualTo("IMPORT");
    assertThat(SystemPipelineCatalog.forJobType("EXPORT").orElseThrow().stages())
        .containsExactly("PREPARE", "GENERATE", "STORE", "REGISTER", "COMPLETE");
    assertThat(SystemPipelineCatalog.forJobType("BUNDLE_EXPORT").orElseThrow().code())
        .isEqualTo("EXPORT");
    assertThat(SystemPipelineCatalog.forJobType("PROCESS").orElseThrow().stages())
        .containsExactly("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(SystemPipelineCatalog.forJobType("DISPATCH").orElseThrow().stages())
        .containsExactly("PREPARE", "DISPATCH", "ACK", "RETRY", "COMPENSATE", "COMPLETE");
    assertThat(SystemPipelineCatalog.forJobType("BUNDLE_DISPATCH").orElseThrow().code())
        .isEqualTo("DISPATCH");
  }

  @DisplayName("非文件作业类型不虚构文件 Pipeline")
  @Test
  void shouldNotInventFilePipelineForNonFileJobTypes() {
    assertThat(SystemPipelineCatalog.forJobType("GENERAL")).isEmpty();
    assertThat(SystemPipelineCatalog.forJobType("ATOMIC")).isEmpty();
    assertThat(SystemPipelineCatalog.forJobType("WORKFLOW")).isEmpty();
    assertThat(SystemPipelineCatalog.forJobType("UNKNOWN")).isEmpty();
    assertThat(SystemPipelineCatalog.forJobType((String) null)).isEmpty();
  }
}
