package com.example.batch.worker.imports.runtime;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ImportWorkerLoop extends AbstractWorkerLoop {

  private final ImportWorkerConfiguration configuration;

  public ImportWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade, ImportWorkerConfiguration configuration) {
    super(workerRuntimeFacade);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "import";
  }

  @Override
  protected int workerPort() {
    return 8083;
  }

  @Scheduled(fixedDelayString = "${batch.worker.import.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
