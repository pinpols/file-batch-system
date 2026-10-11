package io.github.pinpols.batch.console.domain.notification.application.contract.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class AlertRoutingQueryRequest extends PageQueryRequest {

  @ValidTenantId
  private String tenantId;

  @Size(max = 256)
  private String keyword;

  private String routeCode;

  private String team;

  private String severity;

  private Boolean enabled;
}
