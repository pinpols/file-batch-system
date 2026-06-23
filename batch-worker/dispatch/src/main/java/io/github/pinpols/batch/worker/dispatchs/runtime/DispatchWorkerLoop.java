package io.github.pinpols.batch.worker.dispatchs.runtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.application.WorkerRuntimeFacade;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 分发 Worker 心跳循环，定时向 Orchestrator 汇报存活状态。 */
@Service
public class DispatchWorkerLoop extends AbstractWorkerLoop {

  private final DispatchWorkerConfiguration configuration;

  public DispatchWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade,
      BatchDateTimeSupport dateTimeSupport,
      DispatchWorkerConfiguration configuration) {
    super(workerRuntimeFacade, dateTimeSupport);
    this.configuration = configuration;
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
  protected int workerPort() {
    return 8085;
  }

  @Scheduled(fixedDelayString = "${batch.worker.dispatch.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
