package io.github.pinpols.batch.console.web.query;

import lombok.Data;

@Data
public class AlertRoutingQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String routeCode;
  private String team;
  private String severity;
  private Boolean enabled;
}
