package com.example.batch.worker.core.support;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import java.util.Optional;

public interface TaskExecutionWrapper {

  /** 透传 {@link TaskExecutionClient#claim} 语义:Optional.empty=失败,Optional.of(config)=成功。 */
  Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId);

  WorkerExecutionResult execute(PulledTask task);
}
