package io.github.pinpols.batch.trigger.domain.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Data;

@Data
public class TriggerRequestQuery {

  private String tenantId;
  private String jobCode;
  private String triggerType;
  private String requestStatus;
  private PageRequest pageRequest;
}
