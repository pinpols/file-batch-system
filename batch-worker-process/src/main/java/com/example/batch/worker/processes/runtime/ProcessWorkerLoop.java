package com.example.batch.worker.processes.runtime;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.processes.config.ProcessWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 加工 Worker 心跳与注册循环。 */
@Service
public class ProcessWorkerLoop extends AbstractWorkerLoop {

  private final ProcessWorkerConfiguration configuration;

  public ProcessWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade, ProcessWorkerConfiguration configuration) {
    super(workerRuntimeFacade);
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
