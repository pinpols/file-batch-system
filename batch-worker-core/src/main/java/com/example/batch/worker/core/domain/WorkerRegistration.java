package com.example.batch.worker.core.domain;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class WorkerRegistration {

    /**
     * Registry-side worker identity.
     *
     * <p>Use this as the stable runtime instance key for heartbeats and lease ownership.
     */
    private String workerId;
    private String tenantId;
    private String workerType;
    /**
     * Orchestrator scheduling / consumer grouping key.
     */
    private String workerGroup;
    private String status;
    private String host;
    private Integer port;
    private Boolean active;
    private OffsetDateTime registeredAt;
    private OffsetDateTime lastHeartbeatAt;
    /** 进行中的任务数 / 已认领工作量；用于 Orchestrator worker 选择（值越低越优先）。 */
    private Integer currentLoad;
}
