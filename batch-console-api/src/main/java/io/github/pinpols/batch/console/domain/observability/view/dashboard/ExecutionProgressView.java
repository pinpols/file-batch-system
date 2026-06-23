package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.time.Instant;

/** dashboard 单 job + bizDate 的执行进度投影 (job_instance 行级状态 + partition 计数)。 */
public record ExecutionProgressView(
    Long id,
    String jobCode,
    String instanceNo,
    String instanceStatus,
    Integer expectedPartitions,
    Integer successPartitions,
    Integer failedPartitions,
    Instant startedAt,
    Instant finishedAt) {}
