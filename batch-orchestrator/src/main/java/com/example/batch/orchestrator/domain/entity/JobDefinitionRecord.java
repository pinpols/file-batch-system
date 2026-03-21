package com.example.batch.orchestrator.domain.entity;

import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.job_definition")
public class JobDefinitionRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("job_code")
    private String jobCode;
    @Column("job_name")
    private String jobName;
    @Column("job_type")
    private String jobType;
    @Column("biz_type")
    private String bizType;
    @Column("schedule_type")
    private String scheduleType;
    @Column("schedule_expr")
    private String scheduleExpr;
    @Column("timezone")
    private String timezone;
    @Column("worker_group")
    private String workerGroup;
    @Column("queue_code")
    private String queueCode;
    @Column("calendar_code")
    private String calendarCode;
    @Column("window_code")
    private String windowCode;
    @Column("trigger_mode")
    private String triggerMode;
    @Column("dag_enabled")
    private Boolean dagEnabled;
    @Column("shard_strategy")
    private String shardStrategy;
    @Column("retry_policy")
    private String retryPolicy;
    @Column("retry_max_count")
    private Integer retryMaxCount;
    @Column("timeout_seconds")
    private Integer timeoutSeconds;
    @Column("execution_handler")
    private String executionHandler;
    @Column("param_schema")
    private Map<String, Object> paramSchema;
    @Column("priority")
    private Integer priority;
    @Column("default_params")
    private Map<String, Object> defaultParams;
    @Column("version")
    private Integer version;
    @Column("enabled")
    private Boolean enabled;
    @Column("description")
    private String description;
}
