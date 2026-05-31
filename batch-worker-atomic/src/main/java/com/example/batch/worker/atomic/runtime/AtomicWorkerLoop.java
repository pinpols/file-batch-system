package com.example.batch.worker.atomic.runtime;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.atomic.config.AtomicWorkerConfiguration;
import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 专用 Task SPI worker 心跳循环。 */
@Service
public class AtomicWorkerLoop extends AbstractWorkerLoop {

  private final AtomicWorkerConfiguration configuration;

  public AtomicWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade,
      BatchDateTimeSupport dateTimeSupport,
      AtomicWorkerConfiguration configuration) {
    super(workerRuntimeFacade, dateTimeSupport);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "spi";
  }

  @Override
  protected int workerPort() {
    return 8086;
  }

  @Scheduled(fixedDelayString = "${batch.worker.atomic.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
