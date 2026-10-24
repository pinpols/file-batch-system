package io.github.pinpols.batch.worker.processes.runtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import io.github.pinpols.batch.worker.processes.config.ProcessWorkerConfiguration;
import io.github.pinpols.batch.worker.processes.stage.DefaultProcessStageExecutor;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 加工 Worker 心跳与注册循环。 */
@Service
public class ProcessWorkerLoop extends AbstractWorkerLoop {

  private final ProcessWorkerConfiguration configuration;
  private final DefaultProcessStageExecutor processStageExecutor;

  public ProcessWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      ProcessWorkerConfiguration configuration,
      WorkerIdentityProperties identityProperties,
      WorkerRegistryStartupProperties workerRegistryStartupProperties,
      WorkerConcurrencyProperties concurrencyProperties,
      DefaultProcessStageExecutor processStageExecutor) {
    super(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        concurrencyProperties.getMaxConcurrentTasks(),
        identityProperties,
        workerRegistryStartupProperties);
    this.configuration = configuration;
    this.processStageExecutor = processStageExecutor;
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
  protected List<ReplayCapabilityDeclaration> replayCapabilities() {
    return processStageExecutor.replayCapabilityCatalog();
  }

  @Scheduled(fixedDelayString = "${batch.worker.process.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
