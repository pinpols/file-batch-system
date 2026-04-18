package com.example.batch.worker.imports.runtime;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Import Worker 的心跳循环：定时（默认 15s）调用 {@link AbstractWorkerLoop#doHeartbeat} 向
 * Orchestrator 续约，并在首次启动时通过 {@link AbstractWorkerLoop} 完成 Worker 注册。
 *
 * <p>Worker 类型标识为 {@code "import"}，默认端口 8083。
 */
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
