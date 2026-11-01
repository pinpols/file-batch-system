package io.github.pinpols.batch.console.application.ops.query;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record ConsoleWaitRelationshipQuery(
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
