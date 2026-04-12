package com.example.batch.worker.exports.runtime;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 导出 Worker 心跳与注册循环，定期向平台上报 Worker 存活状态。 */
@Service
public class ExportWorkerLoop extends AbstractWorkerLoop {

  private final ExportWorkerConfiguration configuration;

  public ExportWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade, ExportWorkerConfiguration configuration) {
    super(workerRuntimeFacade);
    this.configuration = configuration;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected String workerGroup() {
    return "export";
  }

  @Override
  protected int workerPort() {
    return 8084;
  }

  /** 定时心跳方法，按配置间隔向平台上报 Worker 存活。 */
  @Scheduled(fixedDelayString = "${batch.worker.export.heartbeat-interval-millis:15000}")
  public void heartbeat() {
    doHeartbeat();
  }
}
