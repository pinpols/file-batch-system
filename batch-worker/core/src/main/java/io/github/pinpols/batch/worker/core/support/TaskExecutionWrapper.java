package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import java.util.Optional;

public interface TaskExecutionWrapper {

  /** 透传 {@link TaskExecutionClient#claim} 语义:Optional.empty=失败,Optional.of(config)=成功。 */
  Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId);

  WorkerExecutionResult execute(PulledTask task);
}
