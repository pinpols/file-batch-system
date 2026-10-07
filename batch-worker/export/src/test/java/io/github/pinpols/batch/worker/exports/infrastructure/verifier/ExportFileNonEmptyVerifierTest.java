package io.github.pinpols.batch.worker.exports.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导出文件非空校验单测:记录数与文件大小双指标的通过判定语义")
class ExportFileNonEmptyVerifierTest {

  private final ExportFileNonEmptyVerifier verifier = new ExportFileNonEmptyVerifier();

  @Test
  @DisplayName("记录数为正时校验通过")
  void shouldPass_whenRecordCountPositive() {
    VerifyResult result =
        verifier.verify(contextWith(Map.of(PipelineRuntimeKeys.RECORD_COUNT, 100L)));
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("文件字节数为正时校验通过")
  void shouldPass_whenFileSizePositive() {
    VerifyResult result =
        verifier.verify(contextWith(Map.of(PipelineRuntimeKeys.FILE_SIZE_BYTES, 1024L)));
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("记录数与文件大小同时为零时校验失败,证据带上文件标识")
  void shouldFail_whenRecordCountAndFileSizeBothZero() {
    VerifyResult result = verifier.verify(contextWith(Map.of(
        PipelineRuntimeKeys.RECORD_COUNT,
        0,
        PipelineRuntimeKeys.FILE_SIZE_BYTES,
        0,
        PipelineRuntimeKeys.FILE_ID,
        999L)));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_FILE_EMPTY");
    assertThat(result.evidence()).containsEntry(PipelineRuntimeKeys.FILE_ID, 999L);
  }

  @Test
  @DisplayName("上下文中缺少计数信息时校验失败,返回文件为空")
  void shouldFail_whenCountsMissingFromContext() {
    VerifyResult result = verifier.verify(contextWith(Map.of()));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_FILE_EMPTY");
  }

  @Test
  @DisplayName("计数以数字字符串给出时,同样判定通过")
  void shouldPass_whenCountIsNumericString() {
    VerifyResult result =
        verifier.verify(contextWith(Map.of(PipelineRuntimeKeys.RECORD_COUNT, "42")));
    assertThat(result.passed()).isTrue();
  }

  private static VerifyContext contextWith(Map<String, Object> payload) {
    return VerifyContext.builder()
        .tenantId("t1")
        .jobType(JobType.EXPORT)
        .jobInstanceId(1L)
        .taskId(2L)
        .stageCode("EXPORT_FINALIZE")
        .payload(payload)
        .build();
  }
}
