package io.github.pinpols.batch.console.domain.job.application.contract.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class JobDefinitionQueryRequest extends PageQueryRequest {

  @ValidTenantId
  private String tenantId;

  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @Size(max = 64, message = "exactJobCode too long (max 64)")
  private String exactJobCode;

  @Size(max = 64, message = "dependsOnJobCode too long (max 64)")
  private String dependsOnJobCode;

  @Size(max = 256, message = "jobName too long (max 256)")
  private String jobName;

  @Size(max = 64, message = "jobType too long (max 64)")
  private String jobType;

  @Size(max = 64, message = "workerGroup too long (max 64)")
  private String workerGroup;

  @Size(max = 64, message = "queueCode too long (max 64)")
  private String queueCode;

  @Size(max = 64, message = "scheduleType too long (max 64)")
  private String scheduleType;

  @jakarta.validation.constraints.Pattern(
      regexp = "^(SCHEDULED|DEPENDENCY|RUNTIME_ONLY)?$",
      message = "monitoringCategory must be SCHEDULED, DEPENDENCY, or RUNTIME_ONLY")
  private String monitoringCategory;

  private Boolean enabled = true;

  /** 显式包含已启用和已停用作业；未传时仍只查询启用作业。 */
  private Boolean includeDisabled = false;
}
