package com.example.batch.trigger.web.request;

import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Data;

@Data
public class TriggerLaunchRequest {

  @ValidTenantId private String tenantId;

  @ValidResourceCode private String jobCode;

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
}
