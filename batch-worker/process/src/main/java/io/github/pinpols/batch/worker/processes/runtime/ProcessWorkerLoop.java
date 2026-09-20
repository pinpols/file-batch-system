package io.github.pinpols.batch.worker.processes.runtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import io.github.pinpols.batch.worker.processes.config.ProcessWorkerConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 加工 Worker 心跳与注册循环。 */
@Service
public class ProcessWorkerLoop extends AbstractWorkerLoop {

  private final ProcessWorkerConfiguration configuration;

  public ProcessWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      ProcessWorkerConfiguration configuration,
      WorkerIdentityProperties identityProperties,
      @Value("${batch.worker.max-concurrent-tasks:8}") int maxConcurrentTasks) {
    super(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        maxConcurrentTasks,
        identityProperties);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "process";
  }

  @Override
  protected int workerPort() {
    return 8086;
  }

  @Scheduled(fixedDelayString = "${batch.worker.process.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
