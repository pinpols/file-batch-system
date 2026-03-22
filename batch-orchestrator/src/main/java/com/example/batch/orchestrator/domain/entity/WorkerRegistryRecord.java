package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.example.batch.orchestrator.domain.value.JsonbString;

@Data
@Table(schema = "batch", value = "worker_registry")
public class WorkerRegistryRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("worker_code")
    private String workerCode;
    @Column("worker_group")
    private String workerGroup;
    @Column("capability_tags")
    private JsonbString capabilityTags;
    @Column("resource_tag")
    private String resourceTag;
    @Column("status")
    private String status;
    @Column("heartbeat_at")
    private Instant heartbeatAt;
    @Column("current_load")
    private Integer currentLoad;
    @Column("drain_started_at")
    private Instant drainStartedAt;
    @Column("drain_deadline_at")
    private Instant drainDeadlineAt;
}
