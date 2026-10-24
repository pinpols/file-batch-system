package io.github.pinpols.batch.console.domain.ops.view.cluster;

import java.time.Instant;
import java.time.LocalDate;

/** 实例级集群诊断查询的固定列投影。 */
public record JobInstanceDiagnosticView(
    Long id,
    String tenantId,
    String instanceNo,
    String jobCode,
    LocalDate bizDate,
    String instanceStatus,
    String queueCode,
    String workerGroup,
    String traceId,
    Instant startedAt,
    Instant deadlineAt) {}
