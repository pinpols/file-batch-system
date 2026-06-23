package io.github.pinpols.batch.worker.exports.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExportFileNonEmptyVerifierTest {

  private final ExportFileNonEmptyVerifier verifier = new ExportFileNonEmptyVerifier();

  @Test
  void passesWhenRecordCountPositive() {
    VerifyResult result = verifier.verify(contextWith(Map.of("recordCount", 100L)));
    assertThat(result.passed()).isTrue();
  }

  @Test
  void passesWhenFileSizePositive() {
    VerifyResult result = verifier.verify(contextWith(Map.of("fileSizeBytes", 1024L)));
    assertThat(result.passed()).isTrue();
  }

  @Test
  void failsWhenBothZero() {
    VerifyResult result =
        verifier.verify(contextWith(Map.of("recordCount", 0, "fileSizeBytes", 0, "fileId", 999L)));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_FILE_EMPTY");
    assertThat(result.evidence()).containsEntry("fileId", 999L);
  }

  @Test
  void failsWhenPayloadMissing() {
    VerifyResult result = verifier.verify(contextWith(Map.of()));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_FILE_EMPTY");
  }

  @Test
  void parsesStringNumberFromPayload() {
    VerifyResult result = verifier.verify(contextWith(Map.of("recordCount", "42")));
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
