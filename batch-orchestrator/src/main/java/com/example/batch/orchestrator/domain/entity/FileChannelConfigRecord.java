package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "file_channel_config")
public record FileChannelConfigRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("channel_code") String channelCode,
        @Column("channel_name") String channelName,
        @Column("channel_type") String channelType,
        @Column("enabled") Boolean enabled) {}
