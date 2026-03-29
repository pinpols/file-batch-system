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
    /** In-flight tasks / claimed work; used for orchestrator worker selection (lower preferred). */
    private Integer currentLoad;
}
