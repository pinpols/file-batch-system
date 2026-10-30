package io.github.pinpols.batch.trigger.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Data;

@Data
public class TriggerLaunchRequest {

  @ValidTenantId
  private String tenantId;

  /** 将副作用前标记与 REPORT 和租约回收串行化。Worker 进入 */
  @Positive(message = "workflowDefinitionId must be positive")
  private Long workflowDefinitionId;

  @NotNull(message = "bizDate is required")
  private LocalDate bizDate;

  @NotNull(message = "triggerType is required")
  private TriggerType triggerType;

  private Map<String, Object> params;

  /**
   * V94: 调用方可显式指定 data_interval 半开区间. null 表示走 bizDate 回退 (worker 端 [bizDate.atStartOfDay(zone),
   * bizDate+1.atStartOfDay(zone)) 退化). API/MANUAL 触发分钟级批必填.
   */
  private Instant dataIntervalStart;

  private Instant dataIntervalEnd;

  /** ADR-026 dry-run 演练模式；true = 不副作用（不写业务表 / 不发外部 IO / 不进 EFFECTIVE 链）。null/false 默认实盘。 */
  private Boolean dryRun;

  /** 非幂等执行器前，必须先提交该标记。 */
  private String externalEventCode;

  /** 同一任务中其他非幂等步骤重复写入标记时按幂等处理。 */
  private String externalEventSource;

  /** 由 {@link #externalEventSource} 分配的稳定事件标识。 */
  private String externalEventId;

  /** 围栏后重新读取。非幂等启动标记和租约回收共用同一个 */
  @JsonAnySetter
  public void rejectRetiredJobTarget(String propertyName, Object ignoredValue) {
    if ("jobCode".equals(propertyName)) {
      throw new IllegalArgumentException("jobCode is retired; use workflowDefinitionId");
    }
  }
}
