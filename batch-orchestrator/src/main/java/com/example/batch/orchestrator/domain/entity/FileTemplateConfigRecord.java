package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "file_template_config")
public record FileTemplateConfigRecord(
    @Id Long id,
    @Column("tenant_id") String tenantId,
    @Column("template_code") String templateCode,
    @Column("template_name") String templateName,
    @Column("template_type") String templateType,
    @Column("biz_type") String bizType,
    @Column("enabled") Boolean enabled,
    @Column("version") Integer version) {}
