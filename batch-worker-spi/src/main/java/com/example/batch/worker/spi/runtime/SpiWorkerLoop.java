package com.example.batch.worker.spi.runtime;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.spi.config.SpiWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 专用 Task SPI worker 心跳循环。 */
@Service
public class SpiWorkerLoop extends AbstractWorkerLoop {

  private final SpiWorkerConfiguration configuration;

  public SpiWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade,
      BatchDateTimeSupport dateTimeSupport,
      SpiWorkerConfiguration configuration) {
    super(workerRuntimeFacade, dateTimeSupport);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "task";
  }

  @Override
  protected int workerPort() {
    return 8086;
  }

  @Scheduled(fixedDelayString = "${batch.worker.spi.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
