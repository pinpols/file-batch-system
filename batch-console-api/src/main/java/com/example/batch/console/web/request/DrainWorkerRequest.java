package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DrainWorkerRequest {

  @ValidTenantId private String tenantId;

  /** 超时秒数，超时后 Orchestrator 接管；省略则使用服务端默认值（batch.worker.drain.default-timeout-seconds）。 */
  @Min(value = 1, message = "timeoutSeconds must be positive")
  private Integer timeoutSeconds;
}
