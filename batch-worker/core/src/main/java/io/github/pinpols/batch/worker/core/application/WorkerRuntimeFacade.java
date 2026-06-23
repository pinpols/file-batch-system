package io.github.pinpols.batch.worker.core.application;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.TaskClaimItem;
import io.github.pinpols.batch.worker.core.support.TaskClaimResult;
import io.github.pinpols.batch.worker.core.support.TaskExecutionWrapper;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Worker 运行时门面：将 {@link WorkerLifecycleManager}、{@link HeartbeatService} 和 {@link
 * TaskExecutionWrapper} 聚合为单一入口，供 {@link AbstractWorkerLoop} 调用。
 *
 * <p>本类不含业务逻辑，只做方法委托，使 AbstractWorkerLoop 无需直接依赖三个不同的服务接口。
 */
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

  public Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId) {
    return taskExecutionWrapper.claim(tenantId, taskId, workerId);
  }

  public List<TaskClaimResult> claimBatch(List<TaskClaimItem> items) {
    return taskExecutionWrapper.claimBatch(items);
  }

  public WorkerExecutionResult execute(PulledTask task) {
    return taskExecutionWrapper.execute(task);
  }
}
