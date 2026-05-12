package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class AlertRoutingConfigUpsertParam {

  private Long id;
  private String tenantId;
  private String routeCode;
  private String routeName;
  private String team;
  private String alertGroup;
  private String severity;
  private String receiver;
  private String groupBy;
  private Integer groupWaitSeconds;
  private Integer groupIntervalSeconds;
  private Integer repeatIntervalSeconds;
  private Boolean enabled;
  private String description;
  private String createdBy;
  private String updatedBy;
}
