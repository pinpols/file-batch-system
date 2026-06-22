package com.example.batch.worker.core.domain;

/** Worker 完成单次任务执行后向 Orchestrator 上报的结果载体。 包含任务 ID、执行成功标志及描述信息， 对应主链路中的 REPORT 阶段。 */
public record WorkerExecutionResult(String taskId, boolean success, String message) {}
