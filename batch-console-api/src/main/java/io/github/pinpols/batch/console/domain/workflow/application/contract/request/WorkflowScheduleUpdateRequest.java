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

/** 更新 Workflow 自有调度配置时，使用定义版本进行乐观锁校验。 */
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
    if (EmptyChecks.isNotBlank(timezone)) {
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
    if (EmptyChecks.isBlank(effectiveTriggerMode)) {
      effectiveTriggerMode = switch (scheduleType) {
        case "CRON", "FIXED_RATE" -> "SCHEDULED";
        case "EVENT" -> "EVENT";
        default -> "MANUAL";
      };
    }
    boolean scheduledTriggerModeIsIncompatible =
        ("CRON".equals(scheduleType) || "FIXED_RATE".equals(scheduleType))
            && !"SCHEDULED".equals(effectiveTriggerMode)
            && !"MIXED".equals(effectiveTriggerMode);
    if (scheduledTriggerModeIsIncompatible) {
      return false;
    }
    boolean eventScheduleHasIncompatibleTriggerMode = "EVENT".equals(scheduleType)
        && !"EVENT".equals(effectiveTriggerMode)
        && !"MIXED".equals(effectiveTriggerMode);
    if (eventScheduleHasIncompatibleTriggerMode) {
      return false;
    }
    boolean manualTriggerModeIsIncompatible = "MANUAL".equals(scheduleType)
        && !"MANUAL".equals(effectiveTriggerMode)
        && !"API".equals(effectiveTriggerMode)
        && !"MIXED".equals(effectiveTriggerMode);
    if (manualTriggerModeIsIncompatible) {
      return false;
    }
    if ("CRON".equals(scheduleType)) {
      if (EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      try {
        String expression = scheduleExpr.trim();
        String[] fields = expression.split("\\s+");
        String quartzExpression = fields.length == 5 ? "0 " + expression : expression;
        CronExpression.parse(quartzExpression);
      } catch (RuntimeException exception) {
        return false;
      }
    } else if ("FIXED_RATE".equals(scheduleType)) {
      if (EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      try {
        String trimmedExpression = scheduleExpr.trim();
        int interval = Integer.parseInt(trimmedExpression);
        if (interval <= 0) {
          return false;
        }
      } catch (NumberFormatException exception) {
        return false;
      }
    }
    return true;
  }
}
