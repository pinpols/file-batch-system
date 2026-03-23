package com.example.batch.common.constants;

/**
 * Centralized HTTP path constants for the Orchestrator internal API.
 * Used by both the orchestrator controller endpoints and worker-side HTTP clients.
 */
public final class OrchestratorApiPaths {

    private OrchestratorApiPaths() {}

    // Worker lifecycle paths
    public static final String WORKERS_BASE = "/internal/workers";
    public static final String WORKER_REGISTER = WORKERS_BASE + "/register";
    public static final String WORKER_HEARTBEAT = WORKERS_BASE + "/{workerCode}/heartbeat";
    public static final String WORKER_DEACTIVATE = WORKERS_BASE + "/{workerCode}/deactivate";
    public static final String WORKER_STATUS = WORKERS_BASE + "/{workerCode}/status";
    public static final String WORKER_DRAIN = WORKERS_BASE + "/{workerCode}/drain";
    public static final String WORKER_FORCE_OFFLINE = WORKERS_BASE + "/{workerCode}/force-offline";
    public static final String WORKER_CLAIMED_TASKS = WORKERS_BASE + "/{workerCode}/claimed-tasks";

    // Task execution paths
    public static final String TASKS_BASE = "/internal/tasks";
    public static final String TASK_CLAIM = TASKS_BASE + "/{taskId}/claim";
    public static final String TASK_RENEW = TASKS_BASE + "/{taskId}/renew";
    public static final String TASK_REPORT = TASKS_BASE + "/{taskId}/report";
}
