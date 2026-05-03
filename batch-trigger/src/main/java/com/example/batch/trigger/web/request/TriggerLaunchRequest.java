package com.example.batch.trigger.web.request;

import com.example.batch.common.enums.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Data;

@Data
public class TriggerLaunchRequest {

  @NotBlank(message = "tenantId is required")
  private String tenantId;

  @NotBlank(message = "jobCode is required")
  private String jobCode;

  @NotNull(message = "bizDate is required")
  private LocalDate bizDate;

  @NotNull(message = "triggerType is required")
  private TriggerType triggerType;

  private Map<String, Object> params;

  /**
   * V94: 调用方可显式指定 data_interval 半开区间. null 表示走 bizDate 兜底 (worker 端 [bizDate.atStartOfDay(zone),
   * bizDate+1.atStartOfDay(zone)) 退化). API/MANUAL 触发分钟级批必填.
   */
  private Instant dataIntervalStart;

  private Instant dataIntervalEnd;
}
