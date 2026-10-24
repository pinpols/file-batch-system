package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import lombok.Data;
import org.springframework.scheduling.support.CronExpression;

/** Workflow-owned schedule configuration update with optimistic definition-version checking. */
@Data
public class WorkflowScheduleUpdateRequest {

  @ValidTenantId
  private String tenantId;

  @NotNull
  private Integer expectedVersion;

  @NotBlank
  @Pattern(regexp = "^(CRON|FIXED_RATE|MANUAL|EVENT)$")
  private String scheduleType;

  @Size(max = 256)
  private String scheduleExpr;

  @Size(max = 64)
  private String timezone;

  @Pattern(regexp = "^(SCHEDULED|API|MANUAL|EVENT|MIXED)$")
  private String triggerMode;

  @Size(max = 128)
  private String calendarCode;

  @Size(max = 128)
  private String dependsOnJobCode;

  @AssertTrue(message = "workflow schedule expression or timezone is invalid")
  public boolean isScheduleConfigurationValid() {
    if (EmptyChecks.isNotNull(timezone) && EmptyChecks.isNotBlank(timezone)) {
      try {
        ZoneId.of(timezone);
      } catch (RuntimeException exception) {
        return false;
      }
    }
    if (EmptyChecks.isNull(scheduleType)) {
      return false;
    }
    String effectiveTriggerMode = triggerMode;
    if (EmptyChecks.isNull(effectiveTriggerMode) || EmptyChecks.isBlank(effectiveTriggerMode)) {
      effectiveTriggerMode = switch (scheduleType) {
        case "CRON", "FIXED_RATE" -> "SCHEDULED";
        case "EVENT" -> "EVENT";
        default -> "MANUAL";
      };
    }
    if (("CRON".equals(scheduleType) || "FIXED_RATE".equals(scheduleType))
        && !"SCHEDULED".equals(effectiveTriggerMode)
        && !"MIXED".equals(effectiveTriggerMode)) {
      return false;
    }
    if ("EVENT".equals(scheduleType)
        && !"EVENT".equals(effectiveTriggerMode)
        && !"MIXED".equals(effectiveTriggerMode)) {
      return false;
    }
    if ("MANUAL".equals(scheduleType)
        && !"MANUAL".equals(effectiveTriggerMode)
        && !"API".equals(effectiveTriggerMode)
        && !"MIXED".equals(effectiveTriggerMode)) {
      return false;
    }
    if ("CRON".equals(scheduleType)) {
      if (EmptyChecks.isNull(scheduleExpr) || EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      try {
        String expression = scheduleExpr.trim();
        CronExpression.parse(expression.split("\\s+").length == 5 ? "0 " + expression : expression);
      } catch (RuntimeException exception) {
        return false;
      }
    } else if ("FIXED_RATE".equals(scheduleType)) {
      if (EmptyChecks.isNull(scheduleExpr) || EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      try {
        if (Integer.parseInt(scheduleExpr.trim()) <= 0) {
          return false;
        }
      } catch (NumberFormatException exception) {
        return false;
      }
    }
    return true;
  }
}
