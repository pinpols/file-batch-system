package com.example.batch.worker.core.application;

import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.HeartbeatService;
import com.example.batch.worker.core.support.TaskExecutionWrapper;
import com.example.batch.worker.core.support.WorkerLifecycleManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkerRuntimeFacade {

  private final WorkerLifecycleManager workerLifecycleManager;
  private final HeartbeatService heartbeatService;
  private final TaskExecutionWrapper taskExecutionWrapper;

  public WorkerRegistration start(WorkerRegistration registration) {
    return workerLifecycleManager.start(registration);
  }

  public void heartbeat(String workerId) {
    heartbeatService.beat(workerId);
  }

  public void shutdown(String workerId) {
    workerLifecycleManager.shutdown(workerId);
  }

  public boolean claim(String tenantId, Long taskId, String workerId) {
    return taskExecutionWrapper.claim(tenantId, taskId, workerId);
  }

  public WorkerExecutionResult execute(PulledTask task) {
    return taskExecutionWrapper.execute(task);
  }
}
