package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.resource_queue")
public class ResourceQueueRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("queue_code")
    private String queueCode;
    @Column("queue_name")
    private String queueName;
    @Column("queue_type")
    private String queueType;
    @Column("max_running_jobs")
    private Integer maxRunningJobs;
    @Column("max_running_partitions")
    private Integer maxRunningPartitions;
    @Column("max_qps")
    private Integer maxQps;
    @Column("worker_group")
    private String workerGroup;
    @Column("resource_tag")
    private String resourceTag;
    @Column("priority_policy")
    private String priorityPolicy;
    @Column("fair_share_weight")
    private Integer fairShareWeight;
    @Column("enabled")
    private Boolean enabled;
}
