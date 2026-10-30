package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.List;
import lombok.Data;
import org.springframework.scheduling.support.CronExpression;

@Data
public class WorkflowDefinitionSaveRequest {
  @ValidTenantId
  private String tenantId;

  @ValidResourceCode
  private String workflowCode;

  @Size(max = 256)
  private String workflowName;

  private String workflowType;
  private Boolean enabled;

  /** Workflow 负责调度配置；旧版客户端省略该字段时，更新操作应保留原值。 */
  @Pattern(regexp = "^(CRON|FIXED_RATE|MANUAL|EVENT|ONE_TIME)$")
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

  private List<@NotNull @Valid NodeItem> nodes;

  private List<@NotNull @Valid EdgeItem> edges;

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
      return true;
    }
    if ("CRON".equals(scheduleType)) {
      if (EmptyChecks.isNull(scheduleExpr) || EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      try {
        String expression = scheduleExpr.trim();
        CronExpression.parse(expression.split("\\s+").length == 5 ? "0 " + expression : expression);
        return true;
      } catch (RuntimeException exception) {
        return false;
      }
    }
    if ("FIXED_RATE".equals(scheduleType)) {
      if (EmptyChecks.isNull(scheduleExpr) || EmptyChecks.isBlank(scheduleExpr)) {
        return false;
      }
      String expression = scheduleExpr.trim();
      try {
        return Integer.parseInt(expression) > 0;
      } catch (NumberFormatException exception) {
        return false;
      }
    }
    return true;
  }

  @Data
  public static class NodeItem {
    @ValidResourceCode
    private String nodeCode;

    @NotNull
    private String nodeName;

    @NotBlank
    private String nodeType;

    private String relatedJobCode;
    private String workerGroup;
    private String windowCode;
    private Integer nodeOrder;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private String nodeParams;
    private String crossDayDependencies;
    private Integer crossDayDependencyTimeoutSeconds;
    private Boolean enabled;
  }

  @Data
  public static class EdgeItem {
    @ValidResourceCode
    private String fromNodeCode;

    @ValidResourceCode
    private String toNodeCode;

    private String edgeType;
    private String conditionExpr;
    private Boolean enabled;
  }
}
