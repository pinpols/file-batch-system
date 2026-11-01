package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import java.time.LocalDate;
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
  @DisplayName("日历依赖仅允许受支持的规则进入安全投影")
  void shouldKeepSupportedCalendarRulesAndRejectUnknownRule() {
    WaitRelationshipRow valid = row(
        "CALENDAR",
        "[{\"upstreamCode\":\"CAL_A\",\"rule\":\"WAIT_SETTLED\"},{\"upstreamCode\":\"CAL_B\",\"rule\":\"WAIT_CUTOFF\"}]");
    sanitizer.sanitize(valid);
    assertThat(valid.isDependencySnapshotValid()).isTrue();
    assertThat(valid.getDependencySnapshot())
        .isEqualTo(
            "[{\"upstreamCode\":\"CAL_A\",\"rule\":\"WAIT_SETTLED\"},{\"upstreamCode\":\"CAL_B\",\"rule\":\"WAIT_CUTOFF\"}]");

    WaitRelationshipRow invalid =
        row("CALENDAR", "[{\"upstreamCode\":\"CAL_A\",\"rule\":\"UNKNOWN_RULE\"}]");
    sanitizer.sanitize(invalid);
    assertThat(invalid.isDependencySnapshotValid()).isFalse();
    assertThat(invalid.getDependencySnapshot()).isNull();
  }

  @Test
  @DisplayName("上一业务日依赖必须指向当前业务日之前的有效日期")
  void shouldRequireEarlierValidDateForPreviousBusinessDay() {
    WaitRelationshipRow valid = row(
        "JOB",
        "[{\"upstreamJobCode\":\"UP\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\",\"upstreamBizDate\":\"2026-10-23\"}]");
    sanitizer.sanitize(valid);
    assertThat(valid.isDependencySnapshotValid()).isTrue();
    assertThat(valid.getDependencySnapshot())
        .isEqualTo(
            "[{\"upstreamJobCode\":\"UP\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\",\"upstreamBizDate\":\"2026-10-23\"}]");

    WaitRelationshipRow sameDay = row(
        "JOB",
        "[{\"upstreamJobCode\":\"UP\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\",\"upstreamBizDate\":\"2026-10-26\"}]");
    sanitizer.sanitize(sameDay);
    assertThat(sameDay.isDependencySnapshotValid()).isFalse();
    assertThat(sameDay.getDependencySnapshot()).isNull();

    WaitRelationshipRow malformed = row(
        "JOB",
        "[{\"upstreamJobCode\":\"UP\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\",\"upstreamBizDate\":\"not-a-date\"}]");
    sanitizer.sanitize(malformed);
    assertThat(malformed.isDependencySnapshotValid()).isFalse();
    assertThat(malformed.getDependencySnapshot()).isNull();
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
    row.setBizDate(LocalDate.of(2026, 10, 26));
    row.setDependencySnapshot(snapshot);
    return row;
  }
}
