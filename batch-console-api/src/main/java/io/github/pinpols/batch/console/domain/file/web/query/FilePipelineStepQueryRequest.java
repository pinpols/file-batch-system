package io.github.pinpols.batch.console.domain.file.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FilePipelineStepQueryRequest extends PageQueryRequest {

  /** 租户 ID；由 ConsoleTenantGuard 在 service 层 resolve 后强制下推到 mapper。 */
  private String tenantId;

  private Long pipelineInstanceId;
  private String stepCode;
  private String stageCode;
  private String stepStatus;
}
