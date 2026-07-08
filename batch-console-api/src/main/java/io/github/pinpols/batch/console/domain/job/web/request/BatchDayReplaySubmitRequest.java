package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * ADR-020 批次日重放提交/预览请求（console 侧）。字段名与 orchestrator {@code BatchDayReplaySubmitCommand} 逐一对应，
 * 序列化后原样转发到 {@code /internal/orchestrator/batch-day-replay/sessions[/preview]}。
 *
 * <p>tenantId 可省略：由 {@code ConsoleTenantGuard} 按 JWT 解析后强制覆盖，防跨租户提交。
 */
@Data
public class BatchDayReplaySubmitRequest {

  @Size(max = 64, message = "tenantId too long (max 64)")
  private String tenantId;

  @NotBlank
  @Size(max = 128, message = "calendarCode too long (max 128)")
  private String calendarCode;

  @NotBlank @ValidBizDate private String bizDate;

  /** ALL / ALL_FAILED / SUBSET_JOB_CODES / OUTPUTS_ONLY。 */
  @NotBlank
  @Size(max = 64, message = "scope too long (max 64)")
  private String scope;

  /** SUBSET_JOB_CODES 时填；其他 scope 忽略。 */
  private List<@Size(max = 128, message = "jobCode too long (max 128)") String> jobCodes;

  /** OUTPUTS_ONLY 时填具体要 promote 的 result_version id；其他 scope 忽略。 */
  private List<Long> versionIds;

  private String resultPolicy;

  private String configVersionPolicy;

  private Integer configVersion;

  @NotBlank
  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;

  @NotBlank
  @Size(max = 128, message = "requestedBy too long (max 128)")
  private String requestedBy;

  /** true → 跳过审批直接 RUNNING；false → PENDING_APPROVAL 等审批（默认）。 */
  private Boolean autoApprove = Boolean.FALSE;

  private String traceId;
}
