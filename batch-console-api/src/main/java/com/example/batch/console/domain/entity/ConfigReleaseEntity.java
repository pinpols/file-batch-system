package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ConfigReleaseEntity {

    private Long id;
    private String tenantId;
    private String configType;
    private String configKey;
    private String configName;
    private String configStatus;
    private Integer versionNo;
    private String grayScope;
    private String configPayload;
    private Instant effectiveFromAt;
    private Instant effectiveToAt;
    private Instant publishedAt;
    private Instant rolledBackAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
