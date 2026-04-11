package com.example.batch.console.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class ConfigChangeLogEntity {

    private Long id;
    private String tenantId;
    private String configType;
    private String configKey;
    private Integer versionNo;
    private String changeAction;
    private String changeResult;
    private String operatorType;
    private String operatorId;
    private String traceId;
    private String changeSummary;
    private Instant createdAt;
}
