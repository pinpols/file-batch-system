package io.github.pinpols.batch.common.constants;

/** Orchestrator 内部 HTTP API 路径常量集中定义。 编排侧 Controller 与 worker 侧 HTTP 客户端共用。 */
public final class OrchestratorApiPaths {

  private OrchestratorApiPaths() {}

  // Worker 生命周期相关路径
  public static final String WORKERS_BASE = "/internal/workers";
  public static final String WORKER_REGISTER = WORKERS_BASE + "/register";
  public static final String WORKER_HEARTBEAT = WORKERS_BASE + "/{workerCode}/heartbeat";
  public static final String WORKER_DEACTIVATE = WORKERS_BASE + "/{workerCode}/deactivate";
  public static final String WORKER_STATUS = WORKERS_BASE + "/{workerCode}/status";
  public static final String WORKER_DRAIN = WORKERS_BASE + "/{workerCode}/drain";
  public static final String WORKER_FORCE_OFFLINE = WORKERS_BASE + "/{workerCode}/force-offline";
  public static final String WORKER_CLAIMED_TASKS = WORKERS_BASE + "/{workerCode}/claimed-tasks";

  // 任务执行相关路径
  public static final String TASKS_BASE = "/internal/tasks";
  public static final String TASK_CLAIM = TASKS_BASE + "/{taskId}/claim";
  public static final String TASK_RENEW = TASKS_BASE + "/{taskId}/renew";
  public static final String TASK_REPORT = TASKS_BASE + "/{taskId}/report";
}
