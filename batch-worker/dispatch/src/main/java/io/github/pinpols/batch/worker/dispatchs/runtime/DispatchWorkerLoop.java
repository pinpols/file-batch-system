package io.github.pinpols.batch.worker.dispatchs.runtime;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchWorkerConfiguration;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 分发 Worker 心跳循环，定时向 Orchestrator 汇报存活状态。 */
@Service
public class DispatchWorkerLoop extends AbstractWorkerLoop {

  private final DispatchWorkerConfiguration configuration;
  private final BatchTaskExecutorRegistry taskExecutorRegistry;

  public DispatchWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      DispatchWorkerConfiguration configuration,
      WorkerIdentityProperties identityProperties,
      WorkerRegistryStartupProperties workerRegistryStartupProperties,
      WorkerConcurrencyProperties concurrencyProperties,
      BatchTaskExecutorRegistry taskExecutorRegistry) {
    super(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        concurrencyProperties.getMaxConcurrentTasks(),
        identityProperties,
        workerRegistryStartupProperties);
    this.configuration = configuration;
    this.taskExecutorRegistry = taskExecutorRegistry;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "dispatch";
  }

  @Override
  protected List<ReplayCapabilityDeclaration> replayCapabilities() {
    return taskExecutorRegistry.replayCapabilityCatalog();
  }

  @Scheduled(fixedDelayString = "${batch.worker.dispatch.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
