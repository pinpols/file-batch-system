package io.github.pinpols.batch.console.domain.ops.view.cluster;

import java.time.Instant;

/** 活跃任务关联 Worker 异常的固定列投影。 */
public record ActiveTaskWorkerIssueView(
    Long taskId,
    Long jobPartitionId,
    String taskStatus,
    String assignedWorkerCode,
    Instant heartbeatAt,
    String workerStatus,
    Instant workerHeartbeatAt,
    String reasonCode) {}
