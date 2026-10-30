package io.github.pinpols.batch.console.application.contract.request.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 用于比较多个租户同一作业配置的请求。 */
@Data
public class TenantConfigMatrixRequest {

  @NotEmpty(message = "tenantIds must not be empty")
  @Size(max = 50, message = "tenantIds must not exceed 50")
  private List<@NotBlank @Size(min = 1, max = 64) String> tenantIds;

  @NotEmpty(message = "jobCodes must not be empty")
  @Size(max = 100, message = "jobCodes must not exceed 100")
  private List<@NotBlank @Size(min = 1, max = 128) String> jobCodes;

  /**
   * 用于漂移比较的基准租户；未指定时使用 tenantIds 中的第一个租户，可显式指定展示范围之外的基准。
   */
  @Size(max = 64)
  private String baselineTenantId;
}
