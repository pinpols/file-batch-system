package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TriggerRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @NotBlank @ValidBizDate private String bizDate;
  private String triggerType;
  private String payload;

  /** true 时仅执行校验（作业定义是否存在、是否启用等），不真正触发。 */
  private boolean dryRun;
}
