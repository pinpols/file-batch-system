package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(schema = "batch", value = "workflow_definition")
public class WorkflowDefinitionRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("workflow_code")
    private String workflowCode;
    @Column("workflow_name")
    private String workflowName;
    @Column("workflow_type")
    private String workflowType;
    @Column("version")
    private Integer version;
    @Column("enabled")
    private Boolean enabled;
}
