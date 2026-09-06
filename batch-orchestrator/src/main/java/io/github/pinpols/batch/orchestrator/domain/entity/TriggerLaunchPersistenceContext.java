package io.github.pinpols.batch.orchestrator.domain.entity;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import lombok.Data;

/** Launch 前置查询所需的触发请求与最新幂等实例投影。 */
@Data
public class TriggerLaunchPersistenceContext {

  private TriggerRequestEntity triggerRequest;
  private JobInstanceEntity existingInstance;
}
