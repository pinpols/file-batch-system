package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("等待关系快照的安全投影")
class WaitRelationshipSanitizerTest {
  private final WaitRelationshipSanitizer sanitizer =
      new WaitRelationshipSanitizer(new ObjectMapper());

  @Test
  @DisplayName("异常字段和未知原因不进入安全投影")
  void shouldRejectUnexpectedFieldsAndNeverReturnOpaqueSnapshotOrReason() {
    WaitRelationshipRow row = row(
        "JOB",
        "[{\"upstreamJobCode\":\"A\",\"bizDateAlignment\":\"SAME_DAY\",\"secret\":\"do-not-return\"}]");
    row.setReason("unknown-sensitive-value");
    sanitizer.sanitize(row);
    assertThat(row.isDependencySnapshotValid()).isFalse();
    assertThat(row.getDependencySnapshot()).isNull();
    assertThat(row.getReason()).isNull();
  }

  @Test
  @DisplayName("保留已知作业字段和安全原因代码")
  void shouldKeepKnownJobFieldsAndOnlySafeReasonCodes() {
    WaitRelationshipRow row = row(
        "JOB",
        "[{\"upstreamJobCode\":\"UP\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\",\"upstreamBizDate\":null,\"resolutionError\":\"DEPENDENCY_CALENDAR_UNAVAILABLE:private detail\"}]");
    row.setReason("UPSTREAM_NOT_EFFECTIVE:private detail");
    sanitizer.sanitize(row);
    assertThat(row.isDependencySnapshotValid()).isTrue();
    assertThat(row.getDependencySnapshot())
        .contains("DEPENDENCY_CALENDAR_UNAVAILABLE")
        .doesNotContain("private detail");
    assertThat(row.getReason()).isEqualTo("UPSTREAM_NOT_EFFECTIVE");
  }

  @Test
  @DisplayName("保留就绪依赖且容量等待不虚构连线")
  void shouldKeepReadinessCodesAndNotInventCapacityDependencies() {
    WaitRelationshipRow readiness = row("READINESS", "A,B");
    sanitizer.sanitize(readiness);
    assertThat(readiness.isDependencySnapshotValid()).isTrue();
    assertThat(readiness.getDependencySnapshot()).isEqualTo("A,B");
    WaitRelationshipRow capacity = row("CAPACITY_ADMISSION", "unexpected payload");
    sanitizer.sanitize(capacity);
    assertThat(capacity.isDependencySnapshotValid()).isTrue();
    assertThat(capacity.getDependencySnapshot()).isNull();
  }

  private static WaitRelationshipRow row(String type, String snapshot) {
    WaitRelationshipRow row = new WaitRelationshipRow();
    row.setWaitType(type);
    row.setBizDate(java.time.LocalDate.of(2026, 10, 26));
    row.setDependencySnapshot(snapshot);
    return row;
  }
}
