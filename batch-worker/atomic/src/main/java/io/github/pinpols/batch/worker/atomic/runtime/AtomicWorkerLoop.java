package io.github.pinpols.batch.worker.atomic.runtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.atomic.config.AtomicWorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 专用原子任务 worker 心跳循环。 */
@Service
public class AtomicWorkerLoop extends AbstractWorkerLoop {

  private final AtomicWorkerConfiguration configuration;

  public AtomicWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      AtomicWorkerConfiguration configuration,
      WorkerIdentityProperties identityProperties,
      WorkerRegistryStartupProperties workerRegistryStartupProperties,
      WorkerConcurrencyProperties concurrencyProperties) {
    super(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        concurrencyProperties.getMaxConcurrentTasks(),
        identityProperties,
        workerRegistryStartupProperties);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "atomic";
  }

  @Scheduled(fixedDelayString = "${batch.worker.atomic.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
