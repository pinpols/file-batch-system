package io.github.pinpols.batch.worker.exports.runtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.application.WorkerRuntimeFacade;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 导出 Worker 心跳与注册循环，定期向平台上报 Worker 存活状态。 */
@Service
public class ExportWorkerLoop extends AbstractWorkerLoop {

  private final ExportWorkerConfiguration configuration;

  public ExportWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade,
      BatchDateTimeSupport dateTimeSupport,
      ExportWorkerConfiguration configuration) {
    super(workerRuntimeFacade, dateTimeSupport);
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
