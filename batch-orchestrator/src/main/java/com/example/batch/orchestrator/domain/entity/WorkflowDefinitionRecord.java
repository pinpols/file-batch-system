package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "workflow_definition")
public record WorkflowDefinitionRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("workflow_code") String workflowCode,
        @Column("workflow_name") String workflowName,
        @Column("workflow_type") String workflowType,
        @Column("version") Integer version,
        @Column("enabled") Boolean enabled) {}
