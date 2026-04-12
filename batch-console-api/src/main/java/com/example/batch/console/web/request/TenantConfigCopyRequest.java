package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * 跨租户配置复制请求。
 *
 * <p>从源租户读取配置，推送到目标租户列表。
 */
@Data
public class TenantConfigCopyRequest {

  /** 源租户 ID。 */
  @NotBlank(message = "sourceTenantId must not be blank")
  @Size(max = 64)
  private String sourceTenantId;

  /** 目标租户 ID 列表（最多 50 个）。 */
  @NotEmpty(message = "targetTenantIds must not be empty")
  @Size(max = 50, message = "targetTenantIds must not exceed 50")
  private List<@Size(min = 1, max = 64) String> targetTenantIds;

  /** 要复制的配置类型。为空则复制全部 10 类。 */
  private Set<ConfigType> configTypes;

  /** 初始化模式：SKIP_EXISTING 或 UPSERT。默认 SKIP_EXISTING。 */
  private TenantConfigBatchInitRequest.InitMode mode =
      TenantConfigBatchInitRequest.InitMode.SKIP_EXISTING;

  /** 试运行模式。 */
  private boolean dryRun;

  public enum ConfigType {
    JOB_DEFINITION,
    WORKFLOW_DEFINITION,
    PIPELINE_DEFINITION,
    FILE_CHANNEL,
    FILE_TEMPLATE,
    RESOURCE_QUEUE,
    BATCH_WINDOW,
    BUSINESS_CALENDAR,
    QUOTA_POLICY,
    ALERT_ROUTING
  }
}
