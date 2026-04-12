package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;

public interface TaskExecutionWrapper {

  boolean claim(String tenantId, Long taskId, String workerId);

  WorkerExecutionResult execute(PulledTask task);
}
