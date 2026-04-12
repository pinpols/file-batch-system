package com.example.batch.trigger.domain.query;

import com.example.batch.common.model.PageRequest;
import lombok.Data;

@Data
public class TriggerRequestQuery {

  private String tenantId;
  private String jobCode;
  private String triggerType;
  private String requestStatus;
  private PageRequest pageRequest;
}
