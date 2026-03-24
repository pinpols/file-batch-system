package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "worker_registry")
public record WorkerRegistryRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("worker_code") String workerCode,
        @Column("worker_group") String workerGroup,
        @Column("capability_tags") JsonbString capabilityTags,
        @Column("resource_tag") String resourceTag,
        @Column("status") String status,
        @Column("heartbeat_at") Instant heartbeatAt,
        @Column("current_load") Integer currentLoad,
        @Column("drain_started_at") Instant drainStartedAt,
        @Column("drain_deadline_at") Instant drainDeadlineAt
) {
    /** Heartbeat update: status, heartbeat time, load, capability tags. */
    public WorkerRegistryRecord withHeartbeat(String status, Instant heartbeatAt,
                                              Integer currentLoad, JsonbString capabilityTags) {
        return new WorkerRegistryRecord(id, tenantId, workerCode, workerGroup, capabilityTags,
                resourceTag, status, heartbeatAt, currentLoad, drainStartedAt, drainDeadlineAt);
    }

    /** Status-only update (e.g. OFFLINE). */
    public WorkerRegistryRecord withStatus(String status, Instant heartbeatAt) {
        return new WorkerRegistryRecord(id, tenantId, workerCode, workerGroup, capabilityTags,
                resourceTag, status, heartbeatAt, currentLoad, drainStartedAt, drainDeadlineAt);
    }

    /** Start drain: set DRAINING + drain window. */
    public WorkerRegistryRecord withDrain(String status, Instant drainStartedAt,
                                          Instant drainDeadlineAt, Instant heartbeatAt) {
        return new WorkerRegistryRecord(id, tenantId, workerCode, workerGroup, capabilityTags,
                resourceTag, status, heartbeatAt, currentLoad, drainStartedAt, drainDeadlineAt);
    }

    /** Mark decommissioned: clear drain timestamps. */
    public WorkerRegistryRecord withDecommissioned(Instant heartbeatAt) {
        return new WorkerRegistryRecord(id, tenantId, workerCode, workerGroup, capabilityTags,
                resourceTag, "DECOMMISSIONED", heartbeatAt, currentLoad, null, null);
    }
}
