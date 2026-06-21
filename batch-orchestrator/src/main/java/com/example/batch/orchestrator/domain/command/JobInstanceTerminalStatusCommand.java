package com.example.batch.orchestrator.domain.command;

import java.time.Instant;

/** job_instance 写入业务终态并收敛子表时的入参（与 {@code updateStatus} CAS 字段一致）。 */
public record JobInstanceTerminalStatusCommand(
    String tenantId,
    Long id,
    String terminalInstanceStatus,
    Instant finishedAt,
    Long expectedVersion) {}
