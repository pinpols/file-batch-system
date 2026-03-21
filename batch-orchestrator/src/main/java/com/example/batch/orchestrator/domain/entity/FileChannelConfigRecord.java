package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.file_channel_config")
public class FileChannelConfigRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("channel_code")
    private String channelCode;
    @Column("channel_name")
    private String channelName;
    @Column("channel_type")
    private String channelType;
    @Column("enabled")
    private Boolean enabled;
}
