package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

/**
 * ADR-026 演练计划请求（console 侧）。字段名与 orchestrator {@code DryRunPlanRequest} 逐一对应， 序列化后原样转发到 {@code
 * /internal/orchestrator/dry-run/plan}。
 *
 * <p>tenantId 可省略：由 {@code ConsoleTenantGuard} 按 JWT 解析后强制覆盖。level 保持字符串透传， 枚举合法性由 orchestrator 端
 * {@code DryRunLevel} 反序列化裁决。
 */
@Data
public class DryRunPlanRequest {

  @Size(max = 64, message = "tenantId too long (max 64)")
  private String tenantId;

  @NotBlank
  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  /** L2/L3 必填；L1 可空。 */
  @ValidBizDate private String bizDate;

  /** CONFIG_VALIDATE / SCHEDULE_PLAN / EXECUTION_PLAN。 */
  @Size(max = 64, message = "level too long (max 64)")
  private String level;

  /** 可选 effectiveParams，透传给 orchestrator。 */
  private Map<String, Object> params;
}
