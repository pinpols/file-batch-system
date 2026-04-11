package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.value.JsonbString;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

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
        @Column("drain_deadline_at") Instant drainDeadlineAt) {
    /** 心跳更新：状态、心跳时间、负载、能力标签。 */
    public WorkerRegistryRecord withHeartbeat(
            String status, Instant heartbeatAt, Integer currentLoad, JsonbString capabilityTags) {
        return new WorkerRegistryRecord(
                id,
                tenantId,
                workerCode,
                workerGroup,
                capabilityTags,
                resourceTag,
                status,
                heartbeatAt,
                currentLoad,
                drainStartedAt,
                drainDeadlineAt);
    }

    /** 仅更新状态（如 OFFLINE）。 */
    public WorkerRegistryRecord withStatus(String status, Instant heartbeatAt) {
        return new WorkerRegistryRecord(
                id,
                tenantId,
                workerCode,
                workerGroup,
                capabilityTags,
                resourceTag,
                status,
                heartbeatAt,
                currentLoad,
                drainStartedAt,
                drainDeadlineAt);
    }

    /** 开始排空：设置 DRAINING 状态和排空窗口。 */
    public WorkerRegistryRecord withDrain(
            String status, Instant drainStartedAt, Instant drainDeadlineAt, Instant heartbeatAt) {
        return new WorkerRegistryRecord(
                id,
                tenantId,
                workerCode,
                workerGroup,
                capabilityTags,
                resourceTag,
                status,
                heartbeatAt,
                currentLoad,
                drainStartedAt,
                drainDeadlineAt);
    }

    /** 标记已下线：清除排空时间戳。 */
    public WorkerRegistryRecord withDecommissioned(Instant heartbeatAt) {
        return new WorkerRegistryRecord(
                id,
                tenantId,
                workerCode,
                workerGroup,
                capabilityTags,
                resourceTag,
                "DECOMMISSIONED",
                heartbeatAt,
                currentLoad,
                null,
                null);
    }
}
