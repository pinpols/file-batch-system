package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.file_template_config")
public class FileTemplateConfigRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("template_code")
    private String templateCode;
    @Column("template_name")
    private String templateName;
    @Column("template_type")
    private String templateType;
    @Column("biz_type")
    private String bizType;
    @Column("enabled")
    private Boolean enabled;
    @Column("version")
    private Integer version;
}
