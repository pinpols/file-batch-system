package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;

/**
 * REPORT HTTP 投递（含 orchestrator 侧瞬态错误的内置退避重试）。供 {@link HttpTaskExecutionClient} 主路径与 worker 本地
 * report outbox poller 复用。
 */
public interface OrchestratorReportHttpSubmitter {

  /** 调用 orchestrator {@code POST /internal/tasks/{taskId}/report}，失败抛运行时异常；不写入本地 outbox。 */
  void submitReportOverHttp(TaskExecutionReport report);
}
