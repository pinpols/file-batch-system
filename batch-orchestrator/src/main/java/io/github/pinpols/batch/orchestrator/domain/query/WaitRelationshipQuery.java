package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** 以来源和不可变 ID 遍历等待事实；各页是实时读取而非一致性快照。 */
public record WaitRelationshipQuery(
    @NotBlank @Size(max = 64) String tenantId,

    @Pattern(regexp = "^(CALENDAR|JOB|READINESS|CAPACITY_ADMISSION)$")
    String waitType,

    @Size(max = 24) String waitStatus,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateTo,

    @Pattern(regexp = "^(CALENDAR|JOB|READINESS|CAPACITY_ADMISSION)$")
    String cursorType,

    String cursorId,
    @Min(1) @Max(100) Integer pageSize) {
  public static WaitRelationshipQuery all(
      String tenantId, String cursorType, String cursorId, Integer pageSize) {
    return new WaitRelationshipQuery(
        tenantId, null, null, null, null, cursorType, cursorId, pageSize);
  }

  public static WaitRelationshipQuery byType(
      String tenantId, String waitType, String cursorType, String cursorId, Integer pageSize) {
    return new WaitRelationshipQuery(
        tenantId, waitType, null, null, null, cursorType, cursorId, pageSize);
  }

  @AssertTrue(message = "cursor type and positive 64-bit cursor ID must be supplied together")
  public boolean isCursorValid() {
    if (EmptyChecks.isNull(cursorType) && EmptyChecks.isNull(cursorId)) return true;
    if (EmptyChecks.isNull(cursorType)
        || EmptyChecks.isNull(cursorId)
        || !cursorId.matches("[1-9][0-9]{0,18}")) return false;
    try {
      return Long.parseLong(cursorId) > 0;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }
}
